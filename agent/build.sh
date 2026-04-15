#!/bin/bash

cd "$(dirname "$0")"

echo "Building IAST Agent with Maven..."
mvn clean package

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "Agent jar created at: target/iast-agent.jar"
else
    echo "Build failed!"
    exit 1
fi
