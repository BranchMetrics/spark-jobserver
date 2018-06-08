#!/usr/bin/env bash
# Docker environment vars
# NOTE: only static vars not intended to be changed by users should appear here, because
#       this file gets sourced in the middle of server_start.sh, so it will override
#       any env vars set in the docker run command line.
PIDFILE=spark-jobserver.pid
# For Docker, always run start script as foreground
JOBSERVER_FG=1
JOBSERVER_MEMORY=2G
MAX_DIRECT_MEMORY=4G
SPARK_HOME=/root/spark
SPARK_CONF_DIR=$SPARK_HOME/conf