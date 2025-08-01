/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreCharset;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreRole;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTablespace;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectLookupCache;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

public class GaussDBDatabase extends PostgreDatabase {

    private DBRProgressMonitor monitor;
    private static final Log log = Log.getLog(GaussDBDatabase.class);

    /**
     * Character Type
     */
    private String characterType;

    /**
     * dataBase Compatibility Mode
     */
    private String databaseCompatibleMode;

    private boolean isPackageSupported;

    protected GaussDBDatabase(DBRProgressMonitor monitor, GaussDBDataSource dataSource, String name, PostgreRole owner,
        String templateName, PostgreTablespace tablespace, PostgreCharset encoding) throws DBException {
        super(monitor, dataSource, name, owner, templateName, tablespace, encoding);
        this.monitor = monitor;
    }

    protected GaussDBDatabase(DBRProgressMonitor monitor, GaussDBDataSource dataSource, String databaseName) throws DBException {
        super(monitor, dataSource, databaseName);
        this.monitor = monitor;
        readDatabaseInfo(monitor);
        checkInstanceConnection(monitor);
        checkPackageSupport(monitor);
    }

    protected GaussDBDatabase(DBRProgressMonitor monitor, GaussDBDataSource dataSource, ResultSet dbResult) throws DBException {
        super(monitor, dataSource, dbResult);
        this.monitor = monitor;
        init(dbResult);
        checkPackageSupport(monitor);
    }

    @NotNull
    @Override
    public GaussDBDataSource getDataSource() {
        return (GaussDBDataSource) dataSource;
    }

    @Override
    @Property(viewable = true, order = 1)
    public long getObjectId() {
        return super.getObjectId();
    }

    @Property(viewable = true, order = 6)
    public String getCharacterType() {
        return this.characterType;
    }

    @Property(viewable = true, order = 7)
    public String getDatabaseCompatibleMode() {
        return this.databaseCompatibleMode;
    }

    /**
     * is package supported
     *
     * @return is package supported
     */
    public boolean isPackageSupported() {
        return isPackageSupported;
    }

    private void init(ResultSet dbResult) {
        this.databaseCompatibleMode = JDBCUtils.safeGetString(dbResult, "datcompatibility");
        this.characterType = JDBCUtils.safeGetString(dbResult, "datctype");
    }

    public void setDatabaseCompatibleMode(String databaseCompatibleMode) {
        this.databaseCompatibleMode = databaseCompatibleMode;
    }

    public DBRProgressMonitor getMonitor() {
        return monitor;
    }

    public void readDatabaseInfo(DBRProgressMonitor monitor) throws DBCException {
        try (JDBCSession session = getMetaContext().openSession(monitor, DBCExecutionPurpose.META, "Load database info")) {
            try (JDBCPreparedStatement dbStat = session
                .prepareStatement("SELECT db.oid,db.* FROM pg_catalog.pg_database db WHERE datname=?")) {
                dbStat.setString(1, super.getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.nextRow()) {
                        init(dbResult);
                    }
                }
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
        }
    }

    public static class SchemaCache extends JDBCObjectLookupCache<PostgreDatabase, PostgreSchema> {
        @NotNull
        @Override
        public JDBCStatement prepareLookupStatement(@NotNull JDBCSession session, @NotNull PostgreDatabase database,
            @Nullable PostgreSchema object, @Nullable String objectName) throws SQLException {
            StringBuilder catalogQuery = new StringBuilder("SELECT n.oid,n.*,d.description FROM pg_catalog.pg_namespace n\n"
                + "LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=n.oid AND d.objsubid=0 AND d.classoid='pg_namespace'::regclass\n");
            catalogQuery.append(" ORDER BY nspname");
            JDBCPreparedStatement dbStat = session.prepareStatement(catalogQuery.toString());
            return dbStat;
        }

        @Override
        protected PostgreSchema fetchObject(@NotNull JDBCSession session, @NotNull PostgreDatabase owner,
            @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            String name = JDBCUtils.safeGetString(resultSet, "nspname");
            if (name == null) {
                return null;
            }
            return owner.createSchemaImpl(owner, name, resultSet);
        }
    }

    @Override
    public GaussDBSchema createSchemaImpl(@NotNull PostgreDatabase owner, @NotNull String name,
        @NotNull JDBCResultSet resultSet) throws SQLException {
        return new GaussDBSchema(owner, name, resultSet);
    }

    @Override
    public GaussDBSchema createSchemaImpl(@NotNull PostgreDatabase owner, @NotNull String name, @Nullable PostgreRole postgreRole) {
        return new GaussDBSchema(owner, name, postgreRole);
    }
    
    /**
     * set package supported
     *
     * @param isPackageSupported
     *            is package supported
     */
    public void setPackageSupported(boolean isPackageSupported) {
        this.isPackageSupported = isPackageSupported;
    }

    public void checkPackageSupport(DBRProgressMonitor monitor) {
        setPackageSupported("Oracle".equalsIgnoreCase(DBCompatibilityEnum.queryTextByValue(this.databaseCompatibleMode)));
    }

    @Override
    protected String getBaseTypeNameClause() {
        return GaussDBDataTypeCache.getBaseTypeNameClause((PostgreDataSource) dataSource, databaseCompatibleMode);
    }
    
    @Override
    public PostgreDataType getDataType(DBRProgressMonitor monitor, long typeId) {
        if (typeId <= 0) {
            return null;
        }
        PostgreDataType dataType;
        synchronized (dataTypeCache) {
            dataType = dataTypeCache.get(typeId);
            if (dataType != null) {
                return dataType;
            }
        }
        for (PostgreSchema schema : schemaCache.getCachedObjects()) {
            dataType = schema.getDataTypeCache().getDataType(typeId);
            if (dataType != null) {
                synchronized (dataTypeCache) {
                    dataTypeCache.put(typeId, dataType);
                }
                return dataType;
            }
        }
        // Type not found. Let's resolve it
        try {
            dataType = GaussDBDataTypeCache.resolveDataType(monitor, this, typeId);
            dataType.getParentObject().getDataTypeCache().cacheObject(dataType);
            synchronized (dataTypeCache) {
                dataTypeCache.put(dataType.getObjectId(), dataType);
            }
            return dataType;
        } catch (Exception e) {
            log.debug("Can't resolve data type " + typeId, e);
            return null;
        }
    }

    @Override
    public PostgreDataType getDataType(@Nullable DBRProgressMonitor monitor, String typeName) {
        if (typeName.endsWith("[]")) {
            // In some cases ResultSetMetadata returns it as []
            typeName = "_" + typeName.substring(0, typeName.length() - 2);
        }
        {
            // First check system catalog
            final PostgreSchema schema = getCatalogSchema();
            if (schema != null) {
                final PostgreDataType dataType = schema.getDataTypeCache().getCachedObject(typeName);
                if (dataType != null) {
                    return dataType;
                }
            }
        }

        // Check schemas in search path
        PostgreExecutionContext metaContext = getMetaContext();
        List<String> searchPath =
            metaContext == null ? Collections.singletonList(PostgreConstants.CATALOG_SCHEMA_NAME) : metaContext.getSearchPath();
        for (String schemaName : searchPath) {
            final PostgreSchema schema = schemaCache.getCachedObject(schemaName);
            if (schema != null) {
                final PostgreDataType dataType = schema.getDataTypeCache().getCachedObject(typeName);
                if (dataType != null) {
                    return dataType;
                }
            }
        }
        // Check the rest
        for (PostgreSchema schema : schemaCache.getCachedObjects()) {
            if (searchPath.contains(schema.getName())) {
                continue;
            }
            final PostgreDataType dataType = schema.getDataTypeCache().getCachedObject(typeName);
            if (dataType != null) {
                return dataType;
            }
        }

        if (monitor == null || monitor.isForceCacheUsage()) {
            return null;
        }

        // Type not found. Let's resolve it
        try {
            PostgreDataType dataType = GaussDBDataTypeCache.resolveDataType(monitor, this, typeName);
            dataType.getParentObject().getDataTypeCache().cacheObject(dataType);
            synchronized (dataTypeCache) {
                dataTypeCache.put(dataType.getObjectId(), dataType);
            }
            return dataType;
        } catch (Exception e) {
            log.debug("Can't resolve data type '" + typeName + "' in database '" + getName() + "'");
            return null;
        }
    }

}
