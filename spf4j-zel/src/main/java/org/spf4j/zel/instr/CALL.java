/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.zel.instr;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.Callable;
import org.spf4j.zel.vm.EndParamMarker;
import org.spf4j.zel.vm.ExecutionContext;
import org.spf4j.zel.vm.Method;
import org.spf4j.zel.vm.Program;
import org.spf4j.zel.vm.SimpleStack;
import org.spf4j.zel.vm.VMExecutor;
import org.spf4j.zel.vm.ZExecutionException;


/**
 *
 * @author zoly
 */
public final class CALL extends Instruction {

    private static final long serialVersionUID = 759722625722456554L;

    private CALL() {
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("ITC_INHERITANCE_TYPE_CHECKING")
    public void execute(final ExecutionContext context)
            throws ZExecutionException, InterruptedException, VMExecutor.SuspendedException {
        List<Object> parameters = context.popSyncStackValsUntil(EndParamMarker.INSTANCE);
        Object function = context.pop();

        if (function instanceof Program) {
            Program p = (Program) function;
            final ExecutionContext nctx = context.getSubProgramContext(p, parameters);
            Object obj;
            switch (p.getType()) {
                case DETERMINISTIC:
                    SimpleStack params = new SimpleStack();
                    for (int i = parameters.size() - 1; i >= 0; i--) {
                        params.push(parameters.get(i));
                    }
                    params.push(p);
                    obj = context.resultCache.getResult(params, new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            return Program.executeAsync(nctx);
                        }
                    });

                    break;
                case NONDETERMINISTIC:
                        obj = Program.executeAsync(nctx);
                    break;
                default:
                    throw new UnsupportedOperationException(p.getType().toString());
            }
            context.push(obj);
        } else if (function instanceof Method) {
            try {
                context.push(((Method) function).invokeInverseParamOrder(parameters));
            } catch (IllegalAccessException ex) {
                throw new ZExecutionException("cannot invoke " + function, ex);
            } catch (InvocationTargetException ex) {
                throw new ZExecutionException("cannot invoke " + function, ex);
            } catch (Exception ex) {
                throw new ZExecutionException("cannot invoke " + function, ex);
            }
        } else {
            throw new ZExecutionException("cannot invoke " + function);
        }
        context.ip++;
    }

    /**
     * instance
     */
    public static final Instruction INSTANCE = new CALL();
}