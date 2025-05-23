// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("test_hive_truncate_table", "p0,external,hive,external_docker,external_docker_hive") {
    String enabled = context.config.otherConfigs.get("enableHiveTest")
    if (enabled != null && enabled.equalsIgnoreCase("true")) {
        String externalEnvIp = context.config.otherConfigs.get("externalEnvIp")
        String hms_port = context.config.otherConfigs.get("hive3HmsPort")
        String hdfs_port = context.config.otherConfigs.get("hive3HdfsPort")
        String catalog_name = "test_hive3_truncate_table"
        String database_name = "hive_truncate"
        sql """drop catalog if exists ${catalog_name};"""

        sql """
            create catalog if not exists ${catalog_name} properties (
                'type'='hms',
                'hive.metastore.uris' = 'thrift://${externalEnvIp}:${hms_port}',
                'fs.defaultFS' = 'hdfs://${externalEnvIp}:${hdfs_port}',
                'use_meta_cache' = 'true',
                'hive.version'='3.0'
            );
        """

        logger.info("catalog " + catalog_name + " created")
        sql """switch ${catalog_name};"""
        logger.info("switched to catalog " + catalog_name)
        
        sql """create database if not exists ${database_name};"""
        sql """use ${database_name};"""
        logger.info("use database " + database_name)

        // 1. test no partition table
        String table_name = "table_no_pars"
        sql """create table if not exists ${table_name}(col1 bigint, col2 string); """
        checkNereidsExecute("truncate table ${table_name};")

        sql """insert into ${table_name} values(3234424, '44'); """
        sql """insert into ${table_name} values(222, 'aoe'); """
        order_qt_truncate_01_pre """ select * from table_no_pars;  """
        checkNereidsExecute("truncate table ${table_name};")
        order_qt_truncate_01 """ select * from ${table_name}; """

        sql """insert into ${table_name} values(3234424, '44'); """
        checkNereidsExecute("truncate table ${table_name};")
        order_qt_truncate_02 """ select * from ${table_name};  """

        sql """insert into ${table_name} values(222, 'aoe'); """
        checkNereidsExecute("truncate table ${table_name};")
        order_qt_truncate_03 """ select * from ${table_name}; """
        sql """insert into ${table_name} values(222, 'aoe'); """
        sql """drop table ${table_name}"""

        // 2. test partition table
        table_name = "table_with_pars";
        sql """create table if not exists ${table_name}(col1 bigint, col2 string, pt1 varchar, pt2 date)
               partition by list(pt1, pt2)() """
        checkNereidsExecute("truncate table ${table_name};")

        sql """insert into ${table_name} values(33, 'awe', 'wuu', '2023-02-04') """
        sql """insert into ${table_name} values(5535, '3', 'dre', '2023-04-04') """
        order_qt_truncate_04_pre """ select * from ${table_name}; """
        checkNereidsExecute("truncate table ${table_name};")
        order_qt_truncate_04 """ select * from ${table_name}; """

        // 3. test partition table and truncate partitions
        sql """insert into ${table_name} values(44, 'etg', 'wuweu', '2022-02-04') """
        sql """insert into ${table_name} values(88, 'etg', 'wuweu', '2022-01-04') """
        sql """insert into ${table_name} values(095, 'etgf', 'hiyr', '2021-05-06') """
        sql """insert into ${table_name} values(555, 'etgf', 'wet', '2021-05-06') """
        // checkNereidsExecute("truncate table hive_truncate.table_with_pars partition pt1;")
        // order_qt_truncate_05 """ select * from table_with_pars; """
        // checkNereidsExecute("truncate table hive_truncate.table_with_pars partition pt2;")
        order_qt_truncate_06 """ select * from ${table_name}; """

        sql """insert into ${table_name} values(22, 'ttt', 'gggw', '2022-02-04')"""
        sql """insert into ${table_name} values(44, 'etg', 'wuweu', '2022-02-04') """
        sql """insert into ${table_name} values(88, 'etg', 'wuweu', '2022-01-04') """
        sql """insert into ${table_name} values(095, 'etgf', 'hiyr', '2021-05-06') """
        sql """insert into ${table_name} values(555, 'etgf', 'wet', '2021-05-06') """
        // checkNereidsExecute("truncate table ${catalog_name}.hive_truncate.table_with_pars partition pt1;")
        // order_qt_truncate_07 """ select * from table_with_pars; """
        // checkNereidsExecute("truncate table ${catalog_name}.hive_truncate.table_with_pars partition pt2;")
        // order_qt_truncate_08 """ select * from table_with_pars; """
        checkNereidsExecute("truncate table ${table_name}")
        order_qt_truncate_09 """ select * from ${table_name}; """

        sql """drop table ${table_name};"""
        sql """drop database ${database_name};"""
        sql """drop catalog ${catalog_name};"""
    }
}
