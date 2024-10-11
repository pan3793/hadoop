
```
mvn clean install -DskipTests
```

```
mvn test \
  -Dsurefire.excludesFile=$PWD/dev-support/java-17/exclude-tests.txt \
  2>&1 | tee ~/hadoop-test.`date '+%Y%m%d'`.log
```
