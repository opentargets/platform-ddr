import $ivy.`com.github.pathikrit::better-files:3.8.0`
import $ivy.`com.typesafe:config:1.3.4`
import $ivy.`com.github.fommil.netlib:all:1.1.2`
import $ivy.`org.apache.spark::spark-core:2.4.3`
import $ivy.`org.apache.spark::spark-mllib:2.4.3`
import $ivy.`org.apache.spark::spark-sql:2.4.3`
import $ivy.`com.github.pathikrit::better-files:3.8.0`
import better.files.Dsl._
import better.files._
import better.files._
import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel
import breeze.linalg._

object Loaders {
  def loadDrugList(path: String)(implicit ss: SparkSession): DataFrame = {
    val drugList = ss.read.json(path)
      .selectExpr("id as chembl_id", "synonyms", "pref_name", "trade_names")
      .withColumn("drug_names", array_distinct(array_union(col("trade_names"),array_union(array(col("pref_name")), col("synonyms")))))
      .withColumn("_drug_name", explode(col("drug_names")))
      .withColumn("drug_name", lower(col("_drug_name")))
      .select("chembl_id", "drug_name")

    drugList
  }

  def loadTargetListFromDrugs(path: String)(implicit ss: SparkSession): DataFrame = {
    val tList = ss.read.json(path)
      .selectExpr("id as chembl_id", "mechanisms_of_action")
      .withColumn("mechanism_of_action",  explode(col("mechanisms_of_action")))
      .withColumn("target_component", explode(col("mechanism_of_action.target_components")))
      .selectExpr("chembl_id", "target_component.ensembl as target_id").distinct()

    tList
  }

  def loadFDA(path: String)(implicit ss: SparkSession): DataFrame = {
    val fda = ss.read.json(path)

    val columns = Seq("safetyreportid",
      "serious",
      "seriousnessdeath",
      "receivedate",
      "primarysource.qualification as qualification",
      "patient")
    fda.selectExpr(columns:_*)
  }

  def loadBlackList(path: String)(implicit ss: SparkSession): DataFrame = {
    val bl = ss.read
      .option("sep", "\t")
      .option("ignoreLeadingWhiteSpace", "true")
      .option("ignoreTrailingWhiteSpace", "true")
      .csv(path)

    bl.toDF("reactions")
      .orderBy(col("reactions").asc)
  }
}

@main
def main(drugSetPath: String, inputPathPrefix: String, outputPathPrefix: String,
         blackListPath: String): Unit = {
  val sparkConf = new SparkConf()
    .set("spark.driver.maxResultSize", "0")
    .setAppName("similarities-loaders")
    .setMaster("local[*]")

  implicit val ss = SparkSession.builder
    .config(sparkConf)
    .getOrCreate

  import ss.implicits._

  // load blacklist
  val bl = ss.sparkContext
    .broadcast(Loaders.loadBlackList(blackListPath).as[String]
      .collectAsList())

  // the curated drug list we want
  val targetList = Loaders.loadTargetListFromDrugs(drugSetPath)
  val drugList = Loaders.loadDrugList(drugSetPath)
    .join(targetList, Seq("chembl_id"), "left")
    .orderBy(col("drug_name"))
    .cache()

  // load FDA raw lines
  val lines = Loaders.loadFDA(inputPathPrefix)

  val fdasF = lines.withColumn("reaction", explode(col("patient.reaction")))
    // after explode this we will have reaction-drug pairs
    .withColumn("drug", explode(col("patient.drug")))
    // just the fields we want as columns
    .selectExpr("safetyreportid", "serious", "receivedate",
      "ifnull(seriousnessdeath, '0') as seriousness_death",
      "qualification",
      "lower(reaction.reactionmeddrapt) as reaction_reactionmeddrapt" ,
      "ifnull(lower(drug.medicinalproduct), '') as drug_medicinalproduct",
      "ifnull(drug.openfda.generic_name, array()) as drug_generic_name_list",
      "ifnull(drug.openfda.brand_name, array()) as drug_brand_name_list",
      "ifnull(drug.openfda.substance_name, array()) as drug_substance_name_list",
      "drug.drugcharacterization as drugcharacterization")
    // we dont need these columns anymore
    .drop("patient", "reaction", "drug", "_reaction", "seriousnessdeath")
    // delicated filter which should be looked at FDA API to double check
    .where(col("qualification").isInCollection(Seq("1", "2", "3")) and col("drugcharacterization") === "1")
    // drug names comes in a large collection of multiple synonyms but it comes spread across multiple fields
    .withColumn("drug_names", array_distinct(array_union(col("drug_brand_name_list"), array_union(array(col("drug_medicinalproduct")),
      array_union(col("drug_generic_name_list"), col("drug_substance_name_list"))))))
    // the final real drug name
    .withColumn("_drug_name", explode(col("drug_names")))
    .withColumn("drug_name", lower(col("_drug_name")))
    // rubbish out
    .drop("drug_generic_name_list", "drug_substance_name_list", "_drug_name")
    .where($"drug_name".isNotNull and $"reaction_reactionmeddrapt".isNotNull and
      $"safetyreportid".isNotNull and $"seriousness_death" === "0" )

  // remove blacklisted reactions
  val fdasFiltered = fdasF
    .where(not($"reaction_reactionmeddrapt".isInCollection(bl.value)))

  val fdas = fdasFiltered
  // and we will need this processed data later on
    .join(drugList, Seq("drug_name"), "inner")

  // total unique report ids count grouped by reaction
  val aggByReactions = fdas.groupBy(col("reaction_reactionmeddrapt"))
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids_by_reaction")).persist()

  // total unique report ids count grouped by drug name
  val aggByDrugs = fdas.groupBy(col("chembl_id"))
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids_by_drug")).persist()

  // total unique report ids
  val uniqReports = fdas.select("safetyreportid").distinct.count

  // per drug-reaction pair
  val doubleAgg = fdas.groupBy(col("chembl_id"), col("reaction_reactionmeddrapt"))
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids"))
    .withColumnRenamed("uniq_report_ids", "A")
    .join(aggByDrugs, Seq("chembl_id"), "inner")
    .join(aggByReactions, Seq("reaction_reactionmeddrapt"), "inner")
    .withColumn("C", col("uniq_report_ids_by_drug") - col("A"))
    .withColumn("B", col("uniq_report_ids_by_reaction") - col("A"))
    .withColumn("D", lit(uniqReports) - col("uniq_report_ids_by_drug") - col("uniq_report_ids_by_reaction") + col("A"))
    .withColumn("aterm", $"A" * (log($"A") - log($"A" + $"B")))
    .withColumn("cterm", $"C" * (log($"C") - log($"C" + $"D")))
    .withColumn("acterm", ($"A" + $"C") * (log($"A" + $"C") - log($"A" + $"B" + $"C" + $"D")) )
    .withColumn("llr", $"aterm" + $"cterm" - $"acterm")

  val fdasT = fdas.where($"target_id".isNotNull)

  // total unique report ids
  val uniqReportsT = fdasT.select("safetyreportid").distinct.count

  // total unique report ids count grouped by reaction
  val aggByReactionsT = fdasT.groupBy(col("reaction_reactionmeddrapt"))
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids_by_reaction")).persist()

  // total unique report ids count grouped by target id
  val aggByTargets = fdasT.groupBy($"target_id")
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids_by_target")).persist()

  // same LLR but by target id instead of chembl drug id
  val doubleAggTargets = fdasT.groupBy($"target_id", $"reaction_reactionmeddrapt")
    .agg(countDistinct(col("safetyreportid")).as("uniq_report_ids"))
    .withColumnRenamed("uniq_report_ids", "A")
    .join(aggByTargets, Seq("target_id"), "inner")
    .join(aggByReactionsT, Seq("reaction_reactionmeddrapt"), "inner")
    .withColumn("C", col("uniq_report_ids_by_target") - col("A"))
    .withColumn("B", col("uniq_report_ids_by_reaction") - col("A"))
    .withColumn("D", lit(uniqReportsT) - col("uniq_report_ids_by_target") - col("uniq_report_ids_by_reaction") + col("A"))
    .withColumn("aterm", $"A" * (log($"A") - log($"A" + $"B")))
    .withColumn("cterm", $"C" * (log($"C") - log($"C" + $"D")))
    .withColumn("acterm", ($"A" + $"C") * (log($"A" + $"C") - log($"A" + $"B" + $"C" + $"D")) )
    .withColumn("llr", $"aterm" + $"cterm" - $"acterm")

  doubleAgg.write.json(outputPathPrefix + "/agg_by_chembl/")
  doubleAggTargets.write.json(outputPathPrefix + "/agg_by_target/")
}
