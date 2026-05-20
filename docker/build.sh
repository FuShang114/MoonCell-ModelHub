#!/bin/bash

# MoonCell ModelHub - Docker Build Script

IMAGE_NAME="mooncell-modelhub"
IMAGE_TAG="latest"

echo "Building MoonCell ModelHub Docker image..."

docker build -t ${IMAGE_NAME}:${IMAGE_TAG} -f docker/Dockerfile .

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "Run with: docker run -d -p 8080:8080 -v modelhub-data:/app/data ${IMAGE_NAME}:${IMAGE_TAG}"
else
    echo "Build failed!"
    exit 1
fi