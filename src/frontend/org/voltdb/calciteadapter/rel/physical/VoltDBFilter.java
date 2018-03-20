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

package org.voltdb.calciteadapter.rel.physical;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexNode;
import org.voltdb.plannodes.AbstractPlanNode;

public class VoltDBFilter extends Filter implements VoltDBPhysicalRel {

    public VoltDBFilter(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode input,
            RexNode condition) {
            super(cluster, traitSet, input, condition);
            assert VoltDBPhysicalRel.VOLTDB_PHYSICAL.equals(getConvention());
        }

    @Override
    public Filter copy(RelTraitSet traitSet, RelNode input, RexNode condition) {
        return new VoltDBFilter(getCluster(), traitSet, input, condition);
    }

    @Override
    public AbstractPlanNode toPlanNode() {
        // Filter has to be inlined
        throw new IllegalStateException("VoltDBFilter.toPlanNode not implemented");
    }

}
