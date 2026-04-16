#!/bin/bash
# Build and run Spring Boot Demo

cd "$(dirname "$0")"

echo "=== Building Spring Boot Demo ==="
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Starting Spring Boot Application ==="
java -jar target/demo-spring-1.0.0.jar