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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDependency;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

public class GaussDBDependency extends PostgreDependency {
    public GaussDBDependency(PostgreDatabase database, long objectId, String depType, String name,
                             String description, String objectType, String tableName, String schemaName) {
        super(database, objectId, depType, name, description, objectType, tableName, schemaName);
    }

    /**
     * Reads list of dependent objects.
     * SQL query originally copy-pasted from pgAdmin sources with some modifications.
     */
    @NotNull
    public static List<PostgreDependency> readDependencies(
        @Nullable DBRProgressMonitor monitor,
        @NotNull PostgreObject object,
        @NotNull boolean dependents
    ) throws DBCException {
        boolean isMMode = ((GaussDBDatabase) object.getDatabase())
            .getDatabaseCompatibleMode().equals(GaussDBConstants.GAUSSDB_M_COMPATIBLE_MODE);

        List<PostgreDependency> dependencies = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, object, "Load object dependencies")) {
            String queryObjId = dependents ? "objid" : "refobjid";
            String condObjId = dependents ? "refobjid" : "objid";

            String sql =
                "SELECT DISTINCT dep.deptype, dep.classid, dep." + queryObjId + ", cl.relkind, attr.attname," +
                    " pg_get_expr(ad.adbin, ad.adrelid) adefval,\n" +
                    "    CASE WHEN cl.relkind IS NOT NULL THEN cl.relkind::text || COALESCE(dep.objsubid::text, '')::text\n" +
                    "        WHEN tg.oid IS NOT NULL THEN 'T'::text\n" +
                    "        WHEN ty.oid IS NOT NULL THEN 'y'::text\n" +
                    "        WHEN ns.oid IS NOT NULL THEN 'n'::text\n" +
                    "        WHEN pr.oid IS NOT NULL THEN 'p'::text\n" +
                    "        WHEN la.oid IS NOT NULL THEN 'l'::text\n" +
                    "        WHEN rw.oid IS NOT NULL THEN 'R'::text\n" +
                    "        WHEN co.oid IS NOT NULL THEN 'C'::text || contype::text\n" +
                    "        WHEN ad.oid IS NOT NULL THEN 'A'::text\n" +
                    "        ELSE '' END AS type"
                    + ",\n" +
                    buildModeSpecificSelect(isMMode) +
                    "FROM pg_depend dep\n" +
                    "LEFT JOIN pg_class cl ON dep." + queryObjId + "=cl.oid\n" +
                    "LEFT JOIN pg_attribute att ON dep." + queryObjId + "=att.attrelid AND dep.objsubid=att.attnum\n" +
                    "LEFT JOIN pg_namespace nsc ON cl.relnamespace=nsc.oid\n" +
                    "LEFT JOIN pg_proc pr ON dep." + queryObjId + "=pr.oid\n" +
                    "LEFT JOIN pg_namespace nsp ON pr.pronamespace=nsp.oid\n" +
                    "LEFT JOIN pg_trigger tg ON dep." + queryObjId + "=tg.oid\n" +
                    "LEFT JOIN pg_class tgr ON tg.tgrelid=tgr.oid\n" +
                    "LEFT JOIN pg_namespace tgrn ON tgr.relnamespace=tgrn.oid\n" +
                    "LEFT JOIN pg_type ty ON dep." + queryObjId + "=ty.oid\n" +
                    "LEFT JOIN pg_namespace nst ON ty.typnamespace=nst.oid\n" +
                    "LEFT JOIN pg_constraint co ON dep." + queryObjId + "=co.oid\n" +
                    "LEFT JOIN pg_class coc ON co.conrelid=coc.oid\n" +
                    "LEFT JOIN pg_namespace nso ON co.connamespace=nso.oid\n" +
                    "LEFT JOIN pg_rewrite rw ON dep." + queryObjId + "=rw.oid\n" +
                    "LEFT JOIN pg_class clrw ON clrw.oid=rw.ev_class\n" +
                    "LEFT JOIN pg_namespace nsrw ON clrw.relnamespace=nsrw.oid\n" +
                    "LEFT JOIN pg_language la ON dep." + queryObjId + "=la.oid\n" +
                    "LEFT JOIN pg_namespace ns ON dep." + queryObjId + "=ns.oid\n" +
                    "LEFT JOIN pg_attrdef ad ON ad.oid=dep." + queryObjId + "\n" +
                    "LEFT JOIN pg_attribute attr ON attr.attrelid=ad.adrelid and attr.attnum=ad.adnum\n" +
                    "WHERE dep." + condObjId + "=?\n" +
                    "ORDER BY type";

            try (JDBCPreparedStatement dbStat = session.prepareStatement(sql)) {
                dbStat.setLong(1, object.getObjectId());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String tableName = JDBCUtils.safeGetString(dbResult, "ownertable");
                        String schemaName = JDBCUtils.safeGetString(dbResult, "nspname");
                        String objName = JDBCUtils.safeGetString(dbResult, "refname");
                        if (CommonUtils.isEmpty(objName)) {
                            objName = JDBCUtils.safeGetString(dbResult, "attname");
                        }
                        String objDesc = JDBCUtils.safeGetString(dbResult, "adefval");
                        PostgreDependency dependency = new PostgreDependency(
                            object.getDatabase(),
                            JDBCUtils.safeGetLong(dbResult, queryObjId),
                            JDBCUtils.safeGetString(dbResult, "deptype"),
                            objName,
                            objDesc,
                            JDBCUtils.safeGetString(dbResult, "type"),
                            tableName,
                            schemaName
                        );
                        dependencies.add(dependency);
                    }
                }
            }
        } catch (Exception e) {
            throw new DBCException("Error reading dependencies", e);
        }

        return dependencies;
    }

    private static String buildModeSpecificSelect(boolean isMMode) {
        if (isMMode) {
            return "    COALESCE(coc.relname::text, clrw.relname::text, tgr.relname::text) AS ownertable,\n" +
                "    CASE WHEN cl.relname IS NOT NULL AND att.attname IS NOT NULL " +
                "THEN CONCAT(cl.relname, '.', att.attname)::text\n" +
                "    ELSE COALESCE(cl.relname::text, co.conname::text, pr.proname::text, tg.tgname::text, " +
                "ty.typname::text, la.lanname::text, rw.rulename::text, ns.nspname::text) END AS refname,\n" +
                "    COALESCE(nsc.nspname::text, nso.nspname::text, nsp.nspname::text, nst.nspname::text, " +
                "nsrw.nspname::text, tgrn.nspname::text) AS nspname\n";
        } else {
            return "    COALESCE(coc.relname, clrw.relname, tgr.relname) AS ownertable,\n" +
                "    CASE WHEN cl.relname IS NOT NULL AND att.attname IS NOT NULL " +
                "THEN cl.relname || '.' || att.attname\n" +
                "    ELSE COALESCE(cl.relname, co.conname, pr.proname, tg.tgname, ty.typname, " +
                "la.lanname, rw.rulename, ns.nspname) END AS refname,\n" +
                "    COALESCE(nsc.nspname, nso.nspname, nsp.nspname, nst.nspname, " +
                "nsrw.nspname, tgrn.nspname) AS nspname\n";
        }
    }
}
