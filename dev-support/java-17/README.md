
```
mvn clean install -DskipTests
```

```
mvn test --fail-at-end \
  -Dsurefire.excludesFile=$PWD/dev-support/java-17/exclude-tests.txt \
  2>&1 | tee ~/hadoop-test.`date '+%Y%m%d'`.log
```

```
cat hadoop-test.`date '+%Y%m%d'`.log | \
    grep -E 'surefire:3.0.0-M1:test|<<< FAILURE! - in' | \
    grep -o -E 'surefire:3.0.0-M1:test.*|org.apache.hadoop.*'
```
