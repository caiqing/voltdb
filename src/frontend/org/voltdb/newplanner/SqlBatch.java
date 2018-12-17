/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.newplanner;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.voltcore.logging.VoltLogger;
import org.voltdb.CatalogContext;
import org.voltdb.ParameterSet;
import org.voltdb.VoltTypeException;
import org.voltdb.client.ClientResponse;
import org.voltdb.compiler.AdHocPlannedStmtBatch;
import org.voltdb.newplanner.guards.PlannerFallbackException;
import org.voltdb.sysprocs.AdHocNTBase.AdHocPlanningException;

/**
 * The base class for defining a VoltDB SQL query batch containing one or more {@link SqlTask}s.</br>
 * It provides the {@code Iterable<SqlTask>} interface to give access to its contained tasks.
 * @since 8.4
 * @author Yiqun Zhang
 */
public abstract class SqlBatch implements Iterable<SqlTask>  {

    /**
     * Check if the batch is purely comprised of DDL statements.
     * @return true if the batch is comprised of DDL statements only.
     */
    public abstract boolean isDDLBatch();

    /**
     * Get the user parameters.
     * @return the user parameter array.
     */
    public abstract Object[] getUserParameters();

    /**
     * Get the number of tasks in this batch.
     * @return the count of tasks in this batch.
     */
    public abstract int getTaskCount();

    /**
     * Get the execution context of this batch;
     * @return the context.
     */
    abstract Context getContext();

    /**
     * Execute the batch.
     * @return the client response.
     * @throws AdHocPlanningException
     */
    public abstract CompletableFuture<ClientResponse> execute() throws AdHocPlanningException;

    /**
     * Build a {@link SqlBatch} from a {@link ParameterSet} passed through the {@code @AdHoc}
     * system stored procedure.
     * @param params the user parameters. The first parameter is always the query text.
     * The rest parameters are the ones used in the query.
     * @return a {@code SqlBatch} built from the given {@code ParameterSet}.
     * @throws SqlParseException when the query parsing went wrong.
     * @throws PlannerFallbackException when any of the queries in the batch cannot be handled by Calcite.
     * @throws UnsupportedOperationException when the batch is a mixture of
     * DDL and non-DDL statements or has parameters and more than one query at the same time.
     */
    public static SqlBatch from(ParameterSet params, Context context) throws SqlParseException, PlannerFallbackException {
        Object[] paramArray = params.toArray();
        // The first parameter is always the query string.
        String sqlBlock = (String) paramArray[0];
        Object[] userParams = null;
        // AdHoc query can have parameters, see TestAdHocQueries.testAdHocWithParams.
        if (params.size() > 1) {
            userParams = Arrays.copyOfRange(paramArray, 1, paramArray.length);
        }
        SqlBatch batch = new SqlBatchImpl(sqlBlock, userParams, context);
        if (! batch.isDDLBatch()) {
            // Decorate the batch with the add-ons for non-DDL batches so that it will just do the right thing.
            batch = new NonDdlBatch(batch);
        }
        return batch;
    }

    /**
     * The SqlBatch was designed to be self-contained. However, this is not entirely true due to the way that
     * the legacy code was designed. Until I have further reshaped the legacy code path, I will leave an
     * interface to call back into the private methods of {@link org.voltdb.sysprocs.AdHoc}.
     * @author Yiqun Zhang
     * @since 8.4
     */
    public interface Context {
        CompletableFuture<ClientResponse> runDDLBatch(List<String> sqlStatements, List<SqlNode> sqlNodes);
        VoltLogger getLogger();
        void logBatch(final CatalogContext context, final AdHocPlannedStmtBatch batch, final Object[] userParams);
        long getClientHandle();
        CompletableFuture<ClientResponse> createAdHocTransaction(final AdHocPlannedStmtBatch plannedStmtBatch) throws VoltTypeException;
    }
}
