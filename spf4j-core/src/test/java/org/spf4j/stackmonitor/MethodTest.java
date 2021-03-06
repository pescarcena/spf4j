/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.spf4j.stackmonitor;

import junit.framework.Assert;
import org.junit.Test;


/**
 *
 * @author zoly
 */
public final class MethodTest {
    

    @Test
    public void testSomeMethod() {
        Method m1 = Method.getMethod("a", "b");
        Method m2 = Method.getMethod("a", "b");
        Assert.assertTrue(m1 == m2);
        Assert.assertEquals(m2, m1);
        Assert.assertEquals("a", m1.getDeclaringClass());
        Assert.assertEquals("b", m1.getMethodName());
    }
}