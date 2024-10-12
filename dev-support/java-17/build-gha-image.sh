#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e               # exit on error

PROJECT_HOME="$(cd "`dirname "$0"`"/../..; pwd)"

OS_PLATFORM="${1:-ubuntu_24}"
OS_PLATFORM_SUFFIX="_${OS_PLATFORM}"

[ "$#" -gt 0 ] && shift

DOCKER_PLATFORM_ARGS=()
DOCKER_DIR=dev-support/docker
DOCKER_FILE="${DOCKER_DIR}/Dockerfile${OS_PLATFORM_SUFFIX}"

CPU_ARCH=${CPU_ARCH:-$(uname -m)}
if [[ "$CPU_ARCH" == "x86_64" || "$CPU_ARCH" == "amd64" ]]; then
  DOCKER_PLATFORM_ARGS=("--platform" "linux/amd64")
elif [[ "$CPU_ARCH" == "aarch64" || "$CPU_ARCH" == "arm64" ]]; then
  DOCKER_FILE="${DOCKER_DIR}/Dockerfile${OS_PLATFORM_SUFFIX}_aarch64"
  DOCKER_PLATFORM_ARGS=("--platform" "linux/arm64")
fi

if [ ! -e "${DOCKER_FILE}" ] ; then
  echo "'${OS_PLATFORM}' environment not available yet for '${CPU_ARCH}'"
  exit 1
fi

docker build "${DOCKER_PLATFORM_ARGS[@]}" -t hadoop-build -f "${DOCKER_FILE}" "${DOCKER_DIR}"

USER_NAME=ubuntu
USER_ID=1001
GROUP_ID=1001

docker build "${DOCKER_PLATFORM_ARGS[@]}" -t "hadoop-build${OS_PLATFORM_SUFFIX}-gha" - <<UserSpecificDocker
FROM hadoop-build
RUN rm -f /var/log/faillog /var/log/lastlog
RUN userdel -r \$(getent passwd ${USER_ID} | cut -d: -f1) 2>/dev/null || :
RUN groupadd --non-unique -g ${GROUP_ID} ${USER_NAME}
RUN useradd -g ${GROUP_ID} -u ${USER_ID} -k /root -m ${USER_NAME}
RUN echo "${USER_NAME} ALL=NOPASSWD: ALL" > "/etc/sudoers.d/hadoop-build-${USER_ID}"

UserSpecificDocker
