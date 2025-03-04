ARG REGISTRY=lzydock
ARG USER_TEST_BASE_TAG
FROM ${REGISTRY}/user-test-base:${USER_TEST_BASE_TAG}

### copy lzy-py sources & install
COPY docker/tmp-for-context/pylzy/ pylzy
RUN ./conda_prepare.sh pylzy_install 'pylzy'
