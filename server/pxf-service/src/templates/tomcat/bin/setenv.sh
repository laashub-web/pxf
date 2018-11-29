# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Tomcat accepts two parameters JAVA_OPTS and CATALINA_OPTS
# JAVA_OPTS are used during START/STOP/RUN
# CATALINA_OPTS are used during START/RUN

AGENT_PATHS=""
JAVA_AGENTS=""
JAVA_LIBRARY_PATH=""

# DO NOT EDIT VALUES FOR THE VARIABLES BELOW -- they are generated by the start script
PXF_OPTS="-Dpxf.home=${PXF_HOME} -Dpxf.conf=${PXF_CONF} -Dconnector.http.port=${PXF_PORT} -Dpxf.log.dir=${PXF_LOGDIR} -Dpxf.service.user.impersonation.enabled=${PXF_USER_IMPERSONATION} -Dpxf.service.kerberos.keytab=${PXF_KEYTAB} -Dpxf.service.kerberos.principal=${PXF_PRINCIPAL}"
JAVA_OPTS="${PXF_JVM_OPTS} $AGENT_PATHS $JAVA_AGENTS $JAVA_LIBRARY_PATH $PXF_OPTS"

CATALINA_PID="${PXF_RUNDIR}/catalina.pid"
CATALINA_OUT="${PXF_LOGDIR}/catalina.out"
