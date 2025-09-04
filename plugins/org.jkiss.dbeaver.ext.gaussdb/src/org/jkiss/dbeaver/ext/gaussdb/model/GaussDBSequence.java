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

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSchema;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreSequence;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

public class GaussDBSequence extends PostgreSequence {

    private static final Log log = Log.getLog(GaussDBSequence.class);

    public GaussDBSequence(PostgreSchema schema, JDBCResultSet dbResult) {
        super(schema, dbResult);
    }

    public GaussDBSequence(PostgreSchema catalog) {
        super(catalog);
    }

    @Override
    public void loadAdditionalInfo(DBRProgressMonitor monitor) {
        AdditionalInfo additionalInfo = getAdditionalInfo();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load sequence additional info")) {
            try (JDBCPreparedStatement dbSeqStat = session.prepareStatement(
                "SELECT * from " + getFullyQualifiedName(DBPEvaluationContext.DML))) {
                try (JDBCResultSet seqResults = dbSeqStat.executeQuery()) {
                    if (seqResults.next()) {
                        additionalInfo.setStartValue(JDBCUtils.safeGetLong(seqResults, "start_value"));
                        additionalInfo.setLastValue(JDBCUtils.safeGetLongNullable(seqResults, "last_value"));
                        additionalInfo.setMinValue(JDBCUtils.safeGetLong(seqResults, "min_value"));
                        additionalInfo.setMaxValue(JDBCUtils.safeGetLong(seqResults, "max_value"));
                        additionalInfo.setIncrementBy(JDBCUtils.safeGetLong(seqResults, "increment_by"));
                        additionalInfo.setCacheValue(JDBCUtils.safeGetLong(seqResults, "cache_value"));
                        additionalInfo.setCycled(JDBCUtils.safeGetBoolean(seqResults, "is_cycled"));
                    }
                }
            }
            additionalInfo.setLoaded(true);
        } catch (Exception e) {
            log.warn("Error reading sequence values", e);
        }
        ;
    }
}
