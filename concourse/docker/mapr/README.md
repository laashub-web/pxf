# How to build the pxf-dev-mapr docker image locally?

Build the docker images on your local system. Run the following command to
build the image:

### CentOS 6

```
docker build \
  --build-arg=BASE_IMAGE=gcr.io/$PROJECT_ID/gpdb-pxf-dev/gpdb6-centos6-test-pxf:latest \
  --tag=gpdb6-centos6-test-pxf-mapr \
  -f ~/workspace/pxf/concourse/docker/mapr/Dockerfile .
```


### CentOS 7

```
docker build \
  --build-arg=BASE_IMAGE=gcr.io/$PROJECT_ID/gpdb-pxf-dev/gpdb6-centos7-test-pxf:latest \
  --tag=gpdb6-centos7-test-pxf-mapr \
  -f ~/workspace/pxf/concourse/docker/mapr/Dockerfile .
```