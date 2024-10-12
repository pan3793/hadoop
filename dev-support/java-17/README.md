
```
mvn clean install -DskipTests
```

```
mvn test --fail-at-end -Dmaven.test.failure.ignore=true \
  -Dsurefire.excludesFile=$PWD/dev-support/java-17/exclude-tests.txt \
  2>&1 | tee ~/hadoop-test.`date '+%Y%m%d'`.log
```

```
cat hadoop-test.`date '+%Y%m%d'`.log | \
    grep -E 'surefire:3.5.3:test|<<< FAILURE! - in' | \
    grep -o -E 'surefire:3.5.3:test.*|org.apache.hadoop.*'
```

Run test locally
```
./start-build-env.sh
```

Run inside container
```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=${JAVA_HOME}/bin:$PATH
export MAVEN_ARGS="-Pnative -Drequire.fuse -Drequire.openssl -Drequire.snappy -Drequire.valgrind -Drequire.test.libhadoop"
mvn $MAVEN_ARGS clean install -DskipTests
mvn $MAVEN_ARGS test -pl :hadoop-common -Dtest=TestIPC
```
