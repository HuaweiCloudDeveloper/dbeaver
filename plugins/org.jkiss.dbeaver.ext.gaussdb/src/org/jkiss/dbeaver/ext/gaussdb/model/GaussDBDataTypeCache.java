/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataTypeCache;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;

public class GaussDBDataTypeCache extends PostgreDataTypeCache {

    GaussDBDataTypeCache() {
        super();
    }

    @NotNull
    @Override
    protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull PostgreSchema owner) throws SQLException {
        // Initially cache only base types (everything but composite and some arrays)
        PostgreDataSource dataSource = owner.getDataSource();

        String baseTypeNameClause = GaussDBDataTypeCache.getBaseTypeNameClause(
            dataSource,
            ((GaussDBDatabase) owner.getDatabase()).getDatabaseCompatibleMode());
        //or getCurrentDatabase
        boolean readAllTypes = dataSource.supportReadingAllDataTypes();
        boolean supportsSysTypColumn = owner.getDatabase().supportsSysTypCategoryColumn(session);
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT t.oid,t.*,c.relkind,").append(baseTypeNameClause).append(", d.description" +
            "\nFROM pg_catalog.pg_type t");
        if (!readAllTypes && supportsSysTypColumn) {
            sql.append("\nLEFT OUTER JOIN pg_catalog.pg_type et ON et.oid=t.typelem ");
        }
        sql.append("\nLEFT OUTER JOIN pg_catalog.pg_class c ON c.oid=t.typrelid" +
            "\nLEFT OUTER JOIN pg_catalog.pg_description d ON t.oid=d.objoid" +
            "\nWHERE t.typname IS NOT NULL");
        if (!readAllTypes) {
            sql.append("\nAND (c.relkind IS NULL OR c.relkind = 'c')");
            if (supportsSysTypColumn) {
                sql.append(" AND (et.typcategory IS NULL OR et.typcategory <> 'C')");
            }
        }
        sql.append("\nAND t.typnamespace=? ");
        final JDBCPreparedStatement dbStat = session.prepareStatement(sql.toString());
        dbStat.setLong(1, owner.getObjectId());
        return dbStat;
    }

    static String getBaseTypeNameClause(PostgreDataSource dataSource, String databaseCompatibleMode) {
        if ("M".equals(databaseCompatibleMode)) {
            return "t.typname as base_type_name";
        }
        if (dataSource.isServerVersionAtLeast(7, 3)) {
            return "format_type(nullif(t.typbasetype, 0), t.typtypmod) as base_type_name";
        } else {
            return "NULL as base_type_name";
        }
    }

    @NotNull
    static PostgreDataType resolveDataType(@NotNull DBRProgressMonitor monitor, @NotNull PostgreDatabase database, long oid)
            throws SQLException,
            DBException {
        // Initially cache only base types (everything but composite and arrays)
        try (JDBCSession session = database.getDefaultContext(monitor, true)
            .openSession(monitor, DBCExecutionPurpose.META, "Resolve data type by OID")) {
            try (final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT t.oid,t.*,c.relkind," +
                    GaussDBDataTypeCache.getBaseTypeNameClause(database.getDataSource(),
                        ((GaussDBDatabase) database).getDatabaseCompatibleMode())
                    + " FROM pg_catalog.pg_type t" +
                    "\nLEFT OUTER JOIN pg_class c ON c.oid=t.typrelid" +
                    "\nLEFT OUTER JOIN pg_catalog.pg_description d ON t.oid=d.objoid" +
                    "\nWHERE t.oid=? ")) {
                dbStat.setLong(1, oid);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        long schemaOid = JDBCUtils.safeGetLong(dbResult, "typnamespace");
                        PostgreSchema schema = database.getSchema(monitor, schemaOid);
                        if (schema == null) {
                            throw new DBException("Schema " + schemaOid + " not found for data type " + oid);
                        }
                        PostgreDataType dataType = PostgreDataType.readDataType(session, database, dbResult, false);
                        if (dataType != null) {
                            return dataType;
                        }
                    }
                    throw new DBException("Data type " + oid + " not found in database " + database.getName());
                }
            }
        }
    }

    @NotNull
    static PostgreDataType resolveDataType(@NotNull DBRProgressMonitor monitor, @NotNull PostgreDatabase database, String name)
            throws SQLException, DBException {
        // Initially cache only base types (everything but composite and arrays)
        try (JDBCSession session = database.getDefaultContext(monitor, true)
            .openSession(monitor, DBCExecutionPurpose.META, "Resolve data type by name")) {
            try (final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT t.oid,t.*," +
                    GaussDBDataTypeCache.getBaseTypeNameClause(database.getDataSource(),
                        ((GaussDBDatabase) database).getDatabaseCompatibleMode()) +
                    " FROM pg_catalog.pg_type t" +
                    "\nLEFT OUTER JOIN pg_class c ON c.oid=t.typrelid" +
                    "\nLEFT OUTER JOIN pg_catalog.pg_description d ON t.oid=d.objoid" +
                    "\nWHERE t.typname=? ")) {
                dbStat.setString(1, name);
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        long schemaOid = JDBCUtils.safeGetLong(dbResult, "typnamespace");
                        PostgreSchema schema = database.getSchema(monitor, schemaOid);
                        if (schema == null) {
                            throw new DBException("Schema " + schemaOid + " not found for data type " + name);
                        }
                        PostgreDataType dataType = PostgreDataType.readDataType(session, database, dbResult, false);
                        if (dataType != null) {
                            return dataType;
                        }
                    }
                    throw new DBException("Data type " + name + " not found in database " + database.getName());
                }
            }
            //dbStat;
        }
    }
}
