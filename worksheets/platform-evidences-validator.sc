import $ivy.`org.apache.spark::spark-core:2.4.3`
import $ivy.`org.apache.spark::spark-sql:2.4.3`
import org.apache.spark.SparkConf
import org.apache.spark.sql.functions._
import org.apache.spark.sql._
import org.apache.spark.storage.StorageLevel
import org.codehaus.jackson.annotate.JsonRawValue

object Loaders {
  /** Load efo data from efo index dump so this allows us
    * to navegate through the ontology
    */
  def loadEFO(path: String)(implicit ss: SparkSession): DataFrame = {
    val efoCols = Seq("id", "label", "path_code", "therapeutic_label")

    val genAncestors = udf((codes: Seq[Seq[String]]) =>
      codes.view.flatten.toSet.toSeq)
    val stripEfoID = udf((code: String) => code.split("/").last)
    val efos = ss.read.json(path)
      .withColumn("id", stripEfoID(col("code")))
      .withColumn("therapeutic_label", explode(col("therapeutic_labels")))
      .withColumn("path_code", genAncestors(col("path_codes")))

    efos
      .repartitionByRange(col("id"))
      .sortWithinPartitions(col("id"))
  }

  /** Load gene data from gene index dump in order to have a comprehensive list
    * of genes with their symbol biotype and name
    */
  def loadGenes(path: String)(implicit ss: SparkSession): DataFrame = {
    val geneCols = Seq("id", "biotype", "approved_symbol", "approved_name", "go")
    val genes = ss.read.json(path)

    genes
      .drop("_private")
      .repartitionByRange(col("id"))
      .sortWithinPartitions(col("id"))
  }

  def loadEvidences(path: String)(implicit ss: SparkSession): DataFrame = {
    val struct2SortedValues = udf((r: Row) => {
      val fields = r.schema.fieldNames.sorted
      val values = r.getValuesMap[String](fields)
      values.map(_.toString).mkString
    })
    val evidences = ss
      .read
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", "is_valid")
      .json(path)

    evidences
      .withColumn("filename", input_file_name)
      .withColumn("hash_raw", struct2SortedValues(col("unique_association_fields")))
      .withColumn("hash_digest",sha2(col("hash_raw"), 256))
  }
}

@main
def main(evidencePath: String, outputPathPrefix: String = "out/"): Unit = {
  val sparkConf = new SparkConf()
    .setAppName("similarities-loaders")
    .setMaster("local[*]")

  implicit val ss = SparkSession.builder
    .config(sparkConf)
    .getOrCreate

//  val genes = Loaders.loadGenes(inputPathPrefix + "19.04_gene-data.json")
//  val diseases = Loaders.loadEFO(inputPathPrefix + "19.04_efo-data.json")

  val evidences = Loaders.loadEvidences(evidencePath)
  evidences
    .select("unique_association_fields", "hash_raw", "hash_digest")
    .show(false)
}
