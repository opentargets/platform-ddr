# Platform DDR

### Build the code

You only need `sbt >= 1.2.8`
 
```sh
sbt compile
sbt test
sbt assembly
```

Assembly command will generate a _fat-jar_ standalone _jar_ that you can run locally or submit to 
a spark cluster. This _jar_ already contains a default configuration file that you might want to copy
and edit for your own data.

### Run the fat-jar

```sh
# get the fat.jar from the assembly output folder
java -Xms4096M -Xmx4096M -Xss10M -jar <fat.jar> \
    -i 18.12_association_data.json.gz \
    -t 0.1 -e 3 -o output/ -d true \
    --kwargs log-level=WARN
```

### Generate the input file from ES5

```sh
elasticdump --input=http://localhost:9200/19.02_efo-data --output=19.02_efo-data.json --type=data --limit 10000 --sourceOnly
elasticdump --input=http://localhost:9200/19.02_gene-data --output=19.02_gene-data.json --type=data --limit 10000 --sourceOnly
elasticdump --input=http://localhost:9200/19.02_expression-data --output=19.02_expression-data.json --type=data --limit 10000 --sourceOnly
elasticdump --input=http://localhost:9200/19.02_association-data --output=19.02_association-data.json --type=data --limit=10000 --sourceOnly --searchBody '{"query": { "match_all": {} }, "_source": {"excludes": ["private.*", ".private.*"]}}'
```

# Copyright
Copyright 2014-2018 Biogen, Celgene Corporation, EMBL - European Bioinformatics Institute, GlaxoSmithKline, Takeda Pharmaceutical Company and Wellcome Sanger Institute

This software was developed as part of the Open Targets project. For more information please see: http://www.opentargets.org

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
