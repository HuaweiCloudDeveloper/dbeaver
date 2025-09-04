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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreClass;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreTableBase;
import org.jkiss.dbeaver.ext.postgresql.model.impls.PostgreServerExtensionBase;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

public class PostgreServerGaussDB extends PostgreServerExtensionBase {

    private boolean supportJobs;

    protected PostgreServerGaussDB(PostgreDataSource dataSource) {
        super(dataSource);
        this.supportJobs = false;
    }

    @Override
    public String getServerTypeName() {
        return "GaussDB";
    }

    @Override
    public boolean supportsNativeClient() {
        return false;
    }

    public boolean isSupportJobs() {
        return supportJobs;
    }

    public void setSupportJobs(boolean supportJobs) {
        this.supportJobs = supportJobs;
    }

    @Override
    public boolean supportsExtensions() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return true;
    }

    @Override
    public PostgreDatabase.SchemaCache createSchemaCache(PostgreDatabase database) {
        return new GaussDBSchemaCache();
    }

    @Override
    public PostgreSequence createSequence(@NotNull PostgreSchema schema) {
        return new GaussDBSequence(schema);
    }

    @Override
    public PostgreTableBase createRelationOfClass(PostgreSchema schema, PostgreClass.RelKind kind, JDBCResultSet dbResult) {
        if (kind == PostgreClass.RelKind.S) {
            return new GaussDBSequence(schema, dbResult);
        }
        return super.createRelationOfClass(schema, kind, dbResult);
    }

    @Override
    public PostgreTableBase createNewRelation(DBRProgressMonitor monitor, PostgreSchema schema, PostgreClass.RelKind kind, Object copyFrom)
            throws DBException {
        if (kind == PostgreClass.RelKind.S) {
            return new GaussDBSequence(schema);
        }
        return super.createNewRelation(monitor, schema, kind, copyFrom);
    }

    @Override
    public boolean isPGObject(@NotNull Object object) {
        String className = object.getClass().getName();
        return GaussDBConstants.GAUSSDB_PG_OBJECT_CLASS.equals(className);
    }
}
