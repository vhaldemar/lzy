#!/bin/bash
set -m
java -Xmx4G -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError -Djava.util.concurrent.ForkJoinPool.common.parallelism=32 \
-Dmicronaut.env.deduction=true -Dio.grpc.netty.shaded.io.netty.eventLoopThreads=10 -Dio.grpc.netty.level=DEBUG -Dsun.net.level=DEBUG \
-jar /app/app.jar $@ &

/unified_agent --config /logging.yml &
fg %1
sleep 100h