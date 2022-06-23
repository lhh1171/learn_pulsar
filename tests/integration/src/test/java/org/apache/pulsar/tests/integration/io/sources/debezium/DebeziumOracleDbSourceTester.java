/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests.integration.io.sources.debezium;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.tests.integration.containers.DebeziumOracleDbContainer;
import org.apache.pulsar.tests.integration.containers.PulsarContainer;
import org.apache.pulsar.tests.integration.docker.ContainerExecResult;
import org.apache.pulsar.tests.integration.io.sources.SourceTester;
import org.apache.pulsar.tests.integration.topologies.PulsarCluster;
import org.testcontainers.shaded.com.google.common.base.Preconditions;
import org.testng.util.Strings;

import java.io.Closeable;
import java.util.Map;

/**
 * A tester for testing Debezium OracleDb source.
 */
@Slf4j
public class DebeziumOracleDbSourceTester extends SourceTester<DebeziumOracleDbContainer> implements Closeable {

    private static final String NAME = "debezium-oracle";
    private static final long SLEEP_AFTER_COMMAND_MS = 30_000;

    private final String pulsarServiceUrl;

    @Getter
    private DebeziumOracleDbContainer debeziumOracleDbContainer;

    private final PulsarCluster pulsarCluster;

    public DebeziumOracleDbSourceTester(PulsarCluster cluster) {
        super(NAME);
        this.pulsarCluster = cluster;
        this.numEntriesToInsert = 1;
        this.numEntriesExpectAfterStart = 0;

        pulsarServiceUrl = "pulsar://pulsar-proxy:" + PulsarContainer.BROKER_PORT;

        sourceConfig.put("database.hostname", DebeziumOracleDbContainer.NAME);
        sourceConfig.put("database.port", "1521");
        sourceConfig.put("database.user", "dbzuser");
        sourceConfig.put("database.password", "dbz");
        sourceConfig.put("database.server.name", "XE");
        sourceConfig.put("database.dbname", "XE");
        sourceConfig.put("snapshot.mode", "schema_only");

        sourceConfig.put("schema.include.list", "inv");
        sourceConfig.put("database.history.pulsar.service.url", pulsarServiceUrl);
        sourceConfig.put("topic.namespace", "debezium/oracle");
    }

    @Override
    public void setServiceContainer(DebeziumOracleDbContainer container) {
        log.info("start debezium oracle server container.");
        Preconditions.checkState(debeziumOracleDbContainer == null);
        debeziumOracleDbContainer = container;
        pulsarCluster.startService(DebeziumOracleDbContainer.NAME, debeziumOracleDbContainer);
    }

    @SneakyThrows
    @Override
    public void prepareSource() {
        String[] minerCommands = {
            "ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;",
            "ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (PRIMARY KEY) COLUMNS;",
            "alter system switch logfile;"
        };
        String[] commands = {
            "CREATE TABLESPACE inv DATAFILE 'tbs_inv01.dbf' SIZE 200M LOGGING;",
            "CREATE USER inv identified by inv default tablespace inv;",
            "GRANT CREATE TABLE TO inv;",
            "GRANT LOCK ANY TABLE TO inv;",
            "GRANT ALTER ANY TABLE TO inv;",
            "GRANT CREATE SEQUENCE TO inv;",
            "GRANT UNLIMITED TABLESPACE TO inv;",
            "CREATE TABLE inv.customers (" +
                "id NUMBER(9) GENERATED BY DEFAULT ON NULL AS IDENTITY (START WITH 1001) NOT NULL PRIMARY KEY," +
                "first_name VARCHAR2(255) NOT NULL," +
                "last_name VARCHAR2(255) NOT NULL," +
                "email VARCHAR2(255) NOT NULL" +
                ");",
            "ALTER TABLE inv.customers ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;",

            // create user for debezium
            "create role dbz_privs;",
            "grant create session, execute_catalog_role, select any transaction, select any dictionary to dbz_privs;",
            "grant select on SYSTEM.LOGMNR_COL$ to dbz_privs;",
            "grant select on SYSTEM.LOGMNR_OBJ$ to dbz_privs;",
            "grant select on SYSTEM.LOGMNR_USER$ to dbz_privs;",
            "grant select on SYSTEM.LOGMNR_UID$ to dbz_privs;",
            "create user dbzuser identified by dbz default tablespace users;",
            "grant dbz_privs to dbzuser;",
            "alter user dbzuser quota unlimited on users;",
            "grant LOGMINING to dbz_privs;",

            "GRANT CREATE SESSION TO dbzuser;",
            "GRANT SET CONTAINER TO dbzuser;",
            "GRANT SELECT ON V_$DATABASE to dbzuser;",
            "GRANT FLASHBACK ANY TABLE TO dbzuser;",
            "GRANT SELECT ANY TABLE TO dbzuser;",
            "GRANT SELECT_CATALOG_ROLE TO dbzuser;",
            "GRANT EXECUTE_CATALOG_ROLE TO dbzuser;",
            "GRANT SELECT ANY TRANSACTION TO dbzuser;",
            "GRANT LOGMINING TO dbzuser;",

            "GRANT CREATE TABLE TO dbzuser;",
            "GRANT LOCK ANY TABLE TO dbzuser;",
            "GRANT ALTER ANY TABLE TO dbzuser;",
            "GRANT CREATE SEQUENCE TO dbzuser;",

            "GRANT EXECUTE ON DBMS_LOGMNR TO dbzuser;",
            "GRANT EXECUTE ON DBMS_LOGMNR_D TO dbzuser;",

            "GRANT SELECT ON V_$LOG TO dbzuser;",
            "GRANT SELECT ON V_$LOG_HISTORY TO dbzuser;",
            "GRANT SELECT ON V_$LOGMNR_LOGS TO dbzuser;",
            "GRANT SELECT ON V_$LOGMNR_CONTENTS TO dbzuser;",
            "GRANT SELECT ON V_$LOGMNR_PARAMETERS TO dbzuser;",
            "GRANT SELECT ON V_$LOGFILE TO dbzuser;",
            "GRANT SELECT ON V_$ARCHIVED_LOG TO dbzuser;",
            "GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO dbzuser;"
        };

        // good first approximation but still not enough:
        waitForOracleStatus("OPEN");
        Thread.sleep(SLEEP_AFTER_COMMAND_MS);

        // configure logminer
        runSqlCmd("shutdown immediate");

        // good first approximation but still not enough:
        waitForOracleStatus("ORACLE not available");
        Thread.sleep(SLEEP_AFTER_COMMAND_MS);

        runSqlCmd("startup mount");
        // good first approximation but still not enough:
        waitForOracleStatus("MOUNTED");
        Thread.sleep(SLEEP_AFTER_COMMAND_MS);

        runSqlCmd("alter database archivelog;");
        runSqlCmd("alter database open;");
        // good first approximation but still not enough:
        waitForOracleStatus("OPEN");
        Thread.sleep(SLEEP_AFTER_COMMAND_MS);

        for (String cmd: minerCommands) {
            runSqlCmd(cmd);
        }

        // create user/tablespace/table
        for (String cmd: commands) {
            runSqlCmd(cmd);
        }
        // initial event
        runSqlCmd("INSERT INTO inv.customers (first_name, last_name, email) VALUES ('James', 'Bond', 'jbond@null.dev');");
    }

    private void waitForOracleStatus(String status) throws Exception {
        for (int i = 0; i < 1000; i++) {
            ContainerExecResult response = runSqlCmd("SELECT INSTANCE_NAME, STATUS, DATABASE_STATUS FROM V$INSTANCE;");
            if ((response.getStderr() != null && response.getStderr().contains(status))
                    || (response.getStdout() != null && response.getStdout().contains(status))) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Oracle did not initialize properly");
    }

    private ContainerExecResult runSqlCmd(String cmd) throws Exception {
        log.info("Executing \"{}\"", cmd);
        ContainerExecResult response = this.debeziumOracleDbContainer
                .execCmdAsUser("oracle",
                "/bin/bash", "-c",
                "echo \"exit;\" | echo \""
                        + cmd.replace("$", "\\$")
                        + "\" | sqlplus sys/oracle as sysdba"
                );
        if (Strings.isNullOrEmpty(response.getStderr())) {
            log.info("Result of \"{}\":\n{}", cmd, response.getStdout());
        } else {
            log.warn("Result of \"{}\":\n{}\n{}", cmd, response.getStdout(), response.getStderr());
        }
        return response;
    }

    @Override
    public void prepareInsertEvent() throws Exception {
        runSqlCmd("INSERT INTO inv.customers (first_name, last_name, email) VALUES ('John', 'Doe', 'jdoe@null.dev');");
        runSqlCmd("SELECT * FROM inv.customers WHERE last_name='Doe';");
    }

    @Override
    public void prepareDeleteEvent() throws Exception {
        runSqlCmd("DELETE FROM inv.customers WHERE last_name='Doe';");
        runSqlCmd("SELECT * FROM inv.customers WHERE last_name='Doe';");
    }

    @Override
    public void prepareUpdateEvent() throws Exception {
        runSqlCmd("UPDATE inv.customers SET first_name='Jack' WHERE last_name='Doe';");
        runSqlCmd("SELECT * FROM inv.customers WHERE last_name='Doe';");
    }

    @Override
    public Map<String, String> produceSourceMessages(int numMessages) {
        log.info("debezium oracle server already contains preconfigured data.");
        return null;
    }

    @Override
    public int initialDelayForMsgReceive() {
        // LogMiner takes a lot of time to get messages out
        return 30;
    }

    @Override
    public String keyContains() {
        return "XE.INV.CUSTOMERS.Key";
    }

    @Override
    public String valueContains() {
        return "XE.INV.CUSTOMERS.Value";
    }

    @Override
    public void close() {
        if (pulsarCluster != null) {
            PulsarCluster.stopService(DebeziumOracleDbContainer.NAME, debeziumOracleDbContainer);
        }
    }

}
