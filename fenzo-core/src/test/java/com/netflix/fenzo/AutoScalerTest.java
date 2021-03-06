/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.fenzo;

import com.netflix.fenzo.plugins.BinPackingFitnessCalculators;
import com.netflix.fenzo.plugins.HostAttrValueConstraint;
import org.apache.mesos.Protos;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.netflix.fenzo.functions.Action1;
import com.netflix.fenzo.functions.Func1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AutoScalerTest {

    static String hostAttrName = "MachineType";
    final int minIdle=5;
    final int maxIdle=10;
    final long coolDownSecs=2;
    final String hostAttrVal1="4coreServers";
    final String hostAttrVal2="8coreServers";
    int cpus1=4;
    int memory1=40000;
    int cpus2=8;
    int memory2=800;  // make this less than memory1/cpus1 to ensure jobs don't get on these
    final AutoScaleRule rule1 = AutoScaleRuleProvider.createRule(hostAttrVal1, minIdle, maxIdle, coolDownSecs, cpus1/2, memory1/2);
    final AutoScaleRule rule2 = AutoScaleRuleProvider.createRule(hostAttrVal2, minIdle, maxIdle, coolDownSecs, cpus2/2, memory2/2);

    @Before
    public void setUp() throws Exception {

    }

    @After
    public void tearDown() throws Exception {

    }

    private TaskScheduler getScheduler(AutoScaleRule... rules) {
        return getScheduler(false, rules);
    }
    private TaskScheduler getScheduler(final boolean expectLeaseRejection, AutoScaleRule... rules) {
        return getScheduler(expectLeaseRejection, null, 0, 0, rules);
    }
    private TaskScheduler getScheduler(final boolean expectLeaseRejection, final Action1<AutoScaleAction> callback,
                                       AutoScaleRule... rules) {
        return getScheduler(expectLeaseRejection, callback, 0, 0, rules);
    }
    private TaskScheduler getScheduler(final boolean expectLeaseRejection, final Action1<AutoScaleAction> callback,
                                       long delayScaleUpBySecs, long delayScaleDownByDecs,
                                       AutoScaleRule... rules) {
        TaskScheduler.Builder builder = new TaskScheduler.Builder()
                .withAutoScaleByAttributeName(hostAttrName);
        for(AutoScaleRule rule: rules)
            builder.withAutoScaleRule(rule);
        if(callback != null)
            builder.withAutoScalerCallback(callback);
        return builder
                .withDelayAutoscaleDownBySecs(delayScaleDownByDecs)
                .withDelayAutoscaleUpBySecs(delayScaleUpBySecs)
                .withFitnessCalculator(BinPackingFitnessCalculators.cpuMemBinPacker)
                .withLeaseOfferExpirySecs(3600)
                .withLeaseRejectAction(new Action1<VirtualMachineLease>() {
                    @Override
                    public void call(VirtualMachineLease lease) {
                        if(!expectLeaseRejection)
                            Assert.fail("Unexpected to reject lease " + lease.hostname());
                    }
                })
                .build();
    }

    // Test autoscale up on a simple rule
    // - Setup an auto scale rule
    // - keep calling scheduleOnce() periodically until a time greater than autoscale rule's cooldown period
    // - ensure that we get a scale up action within the time
    @Test
    public void scaleUpTest1() throws Exception {
        TaskScheduler scheduler = getScheduler(rule1);
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger scaleUpRequest = new AtomicInteger(0);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if(action instanceof ScaleUpAction) {
                    int needed = ((ScaleUpAction)action).getScaleUpCount();
                    scaleUpRequest.set(needed);
                    latch.countDown();
                }
            }
        });
        List<TaskRequest> requests = new ArrayList<>();
        int i=0;
        do {
            Thread.sleep(1000);
            scheduler.scheduleOnce(requests, leases);
        } while (i++<(coolDownSecs+2) && latch.getCount()>0);
        if(latch.getCount()>0)
            Assert.fail("Timed out scale up action");
        else
            Assert.assertEquals(maxIdle, scaleUpRequest.get());
    }

    // Test scale up action repeating after using up all hosts after first scale up action
    // Setup an auto scale rule
    // start with 0 hosts available
    // keep calling TaskScheduler.scheduleOnce()
    // ensure we get scale up action
    // on first scale up action add some machines
    // use up all of those machines in subsequent scheduling
    // ensure we get another scale up action after cool down time
    @Test
    public void scaleUpTest2() throws Exception {
        TaskScheduler scheduler = getScheduler(rule1);
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean addVMs = new AtomicBoolean(false);
        final List<TaskRequest> requests = new ArrayList<>();
        for(int i=0; i<maxIdle*cpus1; i++)
            requests.add(TaskRequestProvider.getTaskRequest(1.0, 100, 1));
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleUpAction) {
                    if (!addVMs.compareAndSet(false, true)) {
                        // second time around here
                        latch.countDown();
                    }
                }
            }
        });
        int i=0;
        boolean added=false;
        do {
            Thread.sleep(1000);
            if(!added && addVMs.get()) {
                leases.addAll(LeaseProvider.getLeases(maxIdle, cpus1, memory1, 1, 10));
                added=true;
            }
            SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
            Map<String,VMAssignmentResult> resultMap = schedulingResult.getResultMap();
            if(added) {
                int count=0;
                for(VMAssignmentResult result: resultMap.values())
                    count += result.getTasksAssigned().size();
                Assert.assertEquals(requests.size(), count);
                requests.clear();
                leases.clear();
            }
        } while (i++<(2*coolDownSecs+2) && latch.getCount()>0);
        Assert.assertTrue("Second scale up action didn't arrive on time", latch.getCount()==0);
    }

    /**
     * Tests that the rule applies only to the host types specified and not to the other host type.
     * @throws Exception upon any error
     */
    @Test
    public void testOneRuleTwoTypes() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        TaskScheduler scheduler = getScheduler(false, new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleUpAction) {
                    if (action.getRuleName().equals(rule1.getRuleName()))
                        latch.countDown();
                }
            }
        }, rule2);
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        for(int i=0; i<maxIdle*cpus1; i++)
            requests.add(TaskRequestProvider.getTaskRequest(1.0, 100, 1));
        int i=0;
        do {
            Thread.sleep(1000);
            scheduler.scheduleOnce(requests, leases);
        } while(i++<coolDownSecs+2 && latch.getCount()>0);
        if(latch.getCount()<1)
            Assert.fail("Should not have gotten scale up action for " + rule1.getRuleName());
    }

    /**
     * Tests that of the two AutoScale rules setup, scale up action is called only on the one that is actually short.
     * @throws Exception
     */
    @Test
    public void testTwoRulesOneNeedsScaleUp() throws Exception {
        TaskScheduler scheduler = getScheduler(rule1, rule2);
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleUpAction) {
                    if (action.getRuleName().equals(rule2.getRuleName()))
                        latch.countDown();
                }
            }
        });
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal2)).build();
        attributes.put(hostAttrName, attribute);
        for(int l=0; l<maxIdle; l++) {
            leases.add(LeaseProvider.getLeaseOffer("host"+l, 8, 8000, ports, attributes));
        }
        int i=0;
        do {
            Thread.sleep(1000);
            scheduler.scheduleOnce(requests, leases);
            leases.clear();
        } while (i++<coolDownSecs+2 && latch.getCount()>0);
        if(latch.getCount()<1)
            Assert.fail("Scale up action received for " + rule2.getRuleName() + " rule, was expecting only on "
                    + rule1.getRuleName());
    }

    /**
     * Tests simple scale down action on host type that has excess capacity
     * @throws Exception
     */
    @Test
    public void testSimpleScaleDownAction() throws Exception {
        final AtomicInteger scaleDownCount = new AtomicInteger();
        TaskScheduler scheduler = getScheduler(true, rule1);
        final List<TaskRequest> requests = new ArrayList<>();
        for(int c=0; c<cpus1; c++) // add as many 1-CPU requests as #cores on a host
            requests.add(TaskRequestProvider.getTaskRequest(1, 1000, 1));
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleDownAction) {
                    scaleDownCount.set(((ScaleDownAction) action).getHosts().size());
                    latch.countDown();
                }
            }
        });
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes.put(hostAttrName, attribute);
        int excess=3;
        for(int l=0; l<maxIdle+excess; l++) {
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes));
        }
        int i=0;
        boolean first=true;
        do {
            Thread.sleep(1000);
            final SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
            if(first) {
                first=false;
                leases.clear();
                requests.clear();
            }
        } while(i++<coolDownSecs+2 && latch.getCount()>0);
        Assert.assertEquals(0, latch.getCount());
        // expect scale down count to be excess-1 since we used up 1 host
        Assert.assertEquals(excess-1, scaleDownCount.get());
    }

    /**
     * Tests that of the two rules, scale down is called only on the one that is in excess
     * @throws Exception
     */
    @Test
    public void testTwoRuleScaleDownAction() throws Exception {
        TaskScheduler scheduler = getScheduler(true, rule1, rule2);
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final String wrongScaleDownRulename = rule1.getRuleName();
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleDownAction) {
                    if (action.getRuleName().equals(wrongScaleDownRulename))
                        latch.countDown();
                }
            }
        });
        // use up servers covered by rule1
        for(int r=0; r<maxIdle*cpus1; r++)
            requests.add(TaskRequestProvider.getTaskRequest(1, memory1/cpus1, 1));
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes1 = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes1.put(hostAttrName, attribute);
        Map<String, Protos.Attribute> attributes2 = new HashMap<>();
        Protos.Attribute attribute2 = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal2)).build();
        attributes2.put(hostAttrName, attribute2);
        for(int l=0; l<maxIdle+3; l++) {
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes1));
            leases.add(LeaseProvider.getLeaseOffer("host"+100+l, cpus2, memory2, ports, attributes2));
        }
        int i=0;
        boolean first=true;
        do {
            Thread.sleep(1000);
            scheduler.scheduleOnce(requests, leases);
            if(first) {
                first=false;
                requests.clear();
                leases.clear();
            }
        } while (i++<coolDownSecs+2 && latch.getCount()>0);
        if(latch.getCount()<1)
            Assert.fail("Scale down action received for " + wrongScaleDownRulename + " rule, was expecting only on "
                    + rule1.getRuleName());
    }

    // Tests that when scaling down, a balance is achieved across hosts for the given attribute. That is, about equal
    // number of hosts remain after scale down, for each unique value of the given attribute. Say we are trying to
    // balance the number of hosts across the zone attribute, then, after scale down there must be equal number of
    // hosts for each zone.
    @Test
    public void testScaleDownBalanced() throws Exception {
        final String zoneAttrName="Zone";
        final int mxIdl=12;
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] zoneCounts = {0, 0, 0};
        final List<TaskRequest> requests = new ArrayList<>();
        // add enough jobs to fill two machines of zone 0
        List<ConstraintEvaluator> hardConstraints = new ArrayList<>();
        hardConstraints.add(ConstraintsProvider.getHostAttributeHardConstraint(zoneAttrName, "" + 1));
        for(int j=0; j<cpus1*2; j++) {
            requests.add(TaskRequestProvider.getTaskRequest(1, memory1/cpus1, 1, hardConstraints, null));
        }
        final List<VirtualMachineLease> leases = new ArrayList<>();
        final AutoScaleRule rule = AutoScaleRuleProvider.createRule(hostAttrVal1, 3, mxIdl, coolDownSecs, cpus1/2, memory1/2);
        final TaskScheduler scheduler = new TaskScheduler.Builder()
                .withAutoScaleByAttributeName(hostAttrName)
                .withAutoScaleDownBalancedByAttributeName(zoneAttrName)
                .withFitnessCalculator(BinPackingFitnessCalculators.cpuBinPacker)
                .withLeaseOfferExpirySecs(3600)
                .withLeaseRejectAction(new Action1<VirtualMachineLease>() {
                    @Override
                    public void call(VirtualMachineLease lease) {
                        //System.out.println("Rejecting lease on " + lease.hostname());
                    }
                })
                .withAutoScaleRule(rule)
                .build();
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action.getType() == AutoScaleAction.Type.Down) {
                    final Collection<String> hosts = ((ScaleDownAction) action).getHosts();
                    for (String h : hosts) {
                        int zoneNum = Integer.parseInt(h.substring("host".length())) % 3;
                        //System.out.println("Scaling down host " + h);
                        zoneCounts[zoneNum]--;
                    }
                    latch.countDown();
                } else
                    Assert.fail("Wasn't expecting to scale up");
            }
        });
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        // create three attributes, each with unique zone value
        List<Map<String, Protos.Attribute>> attributes = new ArrayList<>(3);
        Protos.Attribute attr = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        for(int i=0; i<3; i++) {
            attributes.add(new HashMap<String, Protos.Attribute>());
            Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(zoneAttrName)
                    .setType(Protos.Value.Type.TEXT)
                    .setText(Protos.Value.Text.newBuilder().setValue(""+i)).build();
            attributes.get(i).put(zoneAttrName, attribute);
            attributes.get(i).put(hostAttrName, attr);
        }
        for(int l=0; l<mxIdl+6; l++) {
            final int zoneNum = l % 3;
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes.get(zoneNum)));
            zoneCounts[zoneNum]++;
        }
        int i=0;
        boolean first=true;
        do {
            Thread.sleep(1000);
            final SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
//            System.out.println("idleVms#: " + schedulingResult.getIdleVMsCount() + ", #leasesAdded=" +
//                    schedulingResult.getLeasesAdded() + ", #totalVms=" + schedulingResult.getTotalVMsCount());
//            System.out.println("#leasesRejected=" + schedulingResult.getLeasesRejected());
            final Map<String, VMAssignmentResult> resultMap = schedulingResult.getResultMap();
            for(Map.Entry<String, VMAssignmentResult> entry: resultMap.entrySet()) {
                final int zn = Integer.parseInt(entry.getValue().getLeasesUsed().get(0).getAttributeMap().get(zoneAttrName).getText().getValue());
                zoneCounts[zn]--;
            }
            if(first) {
                first=false;
                requests.clear();
                leases.clear();
            }
        } while (i++<coolDownSecs+2 && latch.getCount()>0);
        if(latch.getCount()>0)
            Assert.fail("Didn't get scale down");
        for (int zoneCount : zoneCounts) {
            Assert.assertEquals(4, zoneCount);
        }
    }

    /**
     * Test that a scaled down host doesn't get used in spite of receiving an offer for it
     * @throws Exception
     */
    @Test
    public void testScaledDownHostOffer() throws Exception {
        TaskScheduler scheduler = getScheduler(true, rule1);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Collection<String>> scaleDownHostsRef = new AtomicReference<>();
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        for(int j=0; j<cpus1; j++) // fill one machine
            requests.add(TaskRequestProvider.getTaskRequest(1, memory1/cpus1, 1));
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes1 = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes1.put(hostAttrName, attribute);
        int excess=3;
        for(int l=0; l<maxIdle+excess; l++) {
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes1));
        }
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleDownAction) {
                    scaleDownHostsRef.set(((ScaleDownAction) action).getHosts());
                    latch.countDown();
                }
            }
        });
        int i=0;
        boolean first=true;
        while (i++<coolDownSecs+2 && latch.getCount()>0) {
            scheduler.scheduleOnce(requests, leases);
            if(first) {
                first=false;
                requests.clear();
                leases.clear();
            }
            Thread.sleep(1000);
        }
        Assert.assertEquals(0, latch.getCount());
        // remove any existing leases in scheduler
        // now generate offers for hosts that were scale down and ensure they don't get used
        for(String hostname: scaleDownHostsRef.get()) {
            leases.add(LeaseProvider.getLeaseOffer(hostname, cpus1, memory1, ports, attributes1));
        }
        // now try to fill all machines minus one that we filled before
        for(int j=0; j<(maxIdle+excess-1)*cpus1; j++)
            requests.add(TaskRequestProvider.getTaskRequest(1, memory1/cpus1, 1));
        i=0;
        first=true;
        do {
            SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
            if(!schedulingResult.getResultMap().isEmpty()) {
                for(Map.Entry<String, VMAssignmentResult> entry: schedulingResult.getResultMap().entrySet()) {
                    Assert.assertFalse("Did not expect scaled down host " + entry.getKey() + " to be assigned again",
                            isInCollection(entry.getKey(), scaleDownHostsRef.get()));
                    for(int j=0; j<entry.getValue().getTasksAssigned().size(); j++)
                        requests.remove(0);
                }
            }
            if(first) {
                leases.clear();
                first = false;
            }
            Thread.sleep(1000);
        } while(i++<coolDownSecs-1);
    }

    // Tests that resource shortfall is evaluated and scale up happens beyond what would otherwise request only up to
    // maxIdle count for the scaling rule. Also, that scale up from shortfall due to new tasks doesn't wait for cooldown
    @Test
    public void testResourceShortfall() throws Exception {
        TaskScheduler scheduler = getScheduler(true, AutoScaleRuleProvider.createRule(hostAttrVal1, minIdle, maxIdle, coolDownSecs, 1, 1000));
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        for(int i=0; i<rule1.getMaxIdleHostsToKeep()*2; i++)
            requests.add(TaskRequestProvider.getTaskRequest(1, 1000, 1));
        leases.addAll(LeaseProvider.getLeases(2, 1, 1000, 1, 10));
        final AtomicInteger scaleUpRequested = new AtomicInteger();
        final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action instanceof ScaleUpAction) {
                    scaleUpRequested.set(((ScaleUpAction) action).getScaleUpCount());
                    latchRef.get().countDown();
                }
            }
        });
        SchedulingResult schedulingResult = scheduler.scheduleOnce(requests.subList(0, leases.size()), leases);
        Assert.assertNotNull(schedulingResult);
        Thread.sleep(coolDownSecs * 1000 + 1000);
        schedulingResult = scheduler.scheduleOnce(requests.subList(leases.size(), requests.size()), new ArrayList<VirtualMachineLease>());
        Assert.assertNotNull(schedulingResult);
        boolean waitSuccessful = latchRef.get().await(coolDownSecs, TimeUnit.SECONDS);
        Assert.assertTrue(waitSuccessful);
        final int scaleUp = scaleUpRequested.get();
        Assert.assertEquals(requests.size()-leases.size(), scaleUp);
        requests.clear();
        final int newRequests = rule1.getMaxIdleHostsToKeep() * 3;
        for(int i=0; i<newRequests; i++)
            requests.add(TaskRequestProvider.getTaskRequest(1, 1000, 1));
        latchRef.set(new CountDownLatch(1));
        schedulingResult = scheduler.scheduleOnce(requests, new ArrayList<VirtualMachineLease>());
        Assert.assertNotNull(schedulingResult);
        waitSuccessful = latchRef.get().await(coolDownSecs, TimeUnit.SECONDS);
        Assert.assertTrue(waitSuccessful);
        Assert.assertEquals(newRequests, scaleUpRequested.get());
    }

    @Test
    public void testAddingNewRule() throws Exception {
        TaskScheduler scheduler = getScheduler(true, AutoScaleRuleProvider.createRule(hostAttrVal1, minIdle, maxIdle, coolDownSecs, 1, 1000));
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        Map<String, Protos.Attribute> attributes1 = new HashMap<>();
        final Protos.Attribute attribute1 = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes1.put(hostAttrName, attribute1);
        Map<String, Protos.Attribute> attributes2 = new HashMap<>();
        Protos.Attribute attribute2 = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal2)).build();
        attributes2.put(hostAttrName, attribute2);
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        for(int l=0; l<minIdle; l++) {
            leases.add(LeaseProvider.getLeaseOffer("smallhost"+l, cpus1, memory1, ports, attributes1));
            leases.add(LeaseProvider.getLeaseOffer("bighost"+l, cpus2, memory2, ports, attributes2));
        }
        // fill the big hosts, there's no autoscale rule for it
        // make small tasks sticky on small hosts with a constraint
        ConstraintEvaluator attrConstraint = new HostAttrValueConstraint(hostAttrName, new Func1<String, String>() {
            @Override
            public String call(String s) {
                return hostAttrVal1;
            }
        });
        for(int h=0; h<leases.size()/2; h++) {
            requests.add(TaskRequestProvider.getTaskRequest(6.0, 100, 1));
            requests.add(TaskRequestProvider.getTaskRequest(1.0, 10, 1, Collections.singletonList(attrConstraint), null));
        }
        final CountDownLatch latchSmallHosts = new CountDownLatch(1);
        final AtomicReference<Integer> hostsToAdd = new AtomicReference<>(0);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                switch (action.getRuleName()) {
                    case hostAttrVal1:
                        System.out.println("Got scale up for small hosts " + System.currentTimeMillis());
                        hostsToAdd.set(((ScaleUpAction)action).getScaleUpCount());
                        latchSmallHosts.countDown();
                        break;
                    case hostAttrVal2:
                        Assert.fail("Wasn't expecting scale action for big hosts");
                        break;
                    default:
                        Assert.fail("Unexpected scale action rule name " + action.getRuleName());
                }
            }
        });
        for(int i=0; i<coolDownSecs+2; i++) {
            final SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
            if(i==0) {
                //Assert.assertTrue(schedulingResult.getFailures().isEmpty());
                int successes = 0;
                final Map<String, VMAssignmentResult> resultMap = schedulingResult.getResultMap();
                for (Map.Entry<String, VMAssignmentResult> entry : resultMap.entrySet()) {
                    for (TaskAssignmentResult r : entry.getValue().getTasksAssigned())
                        if (r.isSuccessful()) {
                            successes++;
                            scheduler.getTaskAssigner().call(r.getRequest(), entry.getKey());
                            switch ((int)r.getRequest().getCPUs()) {
                                case 1:
                                    Assert.assertTrue("Expecting assignment on small host", entry.getKey().startsWith("smallhost"));
                                    break;
                                case 6:
                                    Assert.assertTrue("Expecting assignment on big host", entry.getKey().startsWith("bighost"));
                                    break;
                                default:
                                    Assert.fail("Unexpected task CPUs: " + r.getRequest().getCPUs());
                            }
                        }
                }
                Assert.assertEquals("#assigned", requests.size(), successes);
                requests.clear();
                leases.clear();
            }
            Thread.sleep(1000);
        }
        if(!latchSmallHosts.await(5, TimeUnit.SECONDS))
            Assert.fail("Small hosts scale up not triggered");
        Assert.assertTrue("Small hosts to add>0", hostsToAdd.get()>0);
        for(int i=0; i<hostsToAdd.get(); i++)
            leases.add(LeaseProvider.getLeaseOffer("smallhost"+100+i, cpus1, memory1, ports, attributes1));
        final CountDownLatch latchBigHosts = new CountDownLatch(1);
        scheduler.addOrReplaceAutoScaleRule(AutoScaleRuleProvider.createRule(hostAttrVal2, minIdle, maxIdle, coolDownSecs, 1, 1000));
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                switch (action.getRuleName()) {
                    case hostAttrVal1:
                        Assert.fail("Wasn't expecting scale action on " + hostAttrVal1);
                        break;
                    case hostAttrVal2:
                        System.out.println("Got scale up for big hosts " + System.currentTimeMillis());
                        latchBigHosts.countDown();
                        break;
                    default:
                        Assert.fail("Unknown scale action rule name: " + action.getRuleName());
                }
            }
        });
        for(int i=0; i<coolDownSecs+2; i++) {
            scheduler.scheduleOnce(requests, leases);
            if (i == 0) {
                leases.clear();
            }
            Thread.sleep(1000);
        }
        if(!latchBigHosts.await(5, TimeUnit.SECONDS))
            Assert.fail("Big hosts scale up not triggered");
    }

    @Test
    public void testRemovingExistingRule() throws Exception {
        TaskScheduler scheduler = getScheduler(true, AutoScaleRuleProvider.createRule(hostAttrVal1, minIdle, maxIdle, coolDownSecs, 1, 1000));
        final List<TaskRequest> requests = new ArrayList<>();
        final List<VirtualMachineLease> leases = new ArrayList<>();
        Map<String, Protos.Attribute> attributes1 = new HashMap<>();
        final Protos.Attribute attribute1 = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes1.put(hostAttrName, attribute1);
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        for(int l=0; l<minIdle; l++) {
            leases.add(LeaseProvider.getLeaseOffer("smallhost"+l, cpus1, memory1, ports, attributes1));
        }
        for(int h=0; h<leases.size()/2; h++) {
            requests.add(TaskRequestProvider.getTaskRequest(3.0, 100, 1));
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean keepGoing = new AtomicBoolean(true);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if (action.getType() == AutoScaleAction.Type.Up) {
                    latch.countDown();
                    keepGoing.set(false);
                }
            }
        });
        for(int i=0; i<coolDownSecs+2 && keepGoing.get(); i++) {
            final SchedulingResult schedulingResult = scheduler.scheduleOnce(requests, leases);
            if(i==0) {
                //Assert.assertTrue(schedulingResult.getFailures().isEmpty());
                int successes = 0;
                final Map<String, VMAssignmentResult> resultMap = schedulingResult.getResultMap();
                for (Map.Entry<String, VMAssignmentResult> entry : resultMap.entrySet()) {
                    for (TaskAssignmentResult r : entry.getValue().getTasksAssigned())
                        if (r.isSuccessful()) {
                            successes++;
                            scheduler.getTaskAssigner().call(r.getRequest(), entry.getKey());
                        }
                }
                Assert.assertEquals("#assigned", requests.size(), successes);
                requests.clear();
                leases.clear();
            }
            Thread.sleep(1000);
        }
        if(!latch.await(2, TimeUnit.SECONDS))
            Assert.fail("Didn't get scale up action");
        scheduler.removeAutoScaleRule(hostAttrVal1);
        scheduler.addOrReplaceAutoScaleRule(AutoScaleRuleProvider.createRule(hostAttrVal2, minIdle, maxIdle, coolDownSecs, 1, 1000));
        final AtomicBoolean gotHost2scaleup = new AtomicBoolean(false);
        scheduler.setAutoscalerCallback(new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction action) {
                if(action.getType() == AutoScaleAction.Type.Up) {
                    switch (action.getRuleName()) {
                        case hostAttrVal1:
                            Assert.fail("Shouldn't have gotten autoscale action");
                            break;
                        case hostAttrVal2:
                            gotHost2scaleup.set(true);
                            break;
                    }
                }
            }
        });
        for(int i=0; i<coolDownSecs+2; i++) {
            scheduler.scheduleOnce(requests, leases);
            Thread.sleep(1000);
        }
        Assert.assertTrue("Host type 2 scale action", gotHost2scaleup.get());
    }

    private boolean isInCollection(String host, Collection<String> hostList) {
        for(String h: hostList)
            if(h.equals(host))
                return true;
        return false;
    }

    // Test that s scale up action doesn't kick in for a very short duration breach of minIdle
    @Test
    public void testDelayedAutoscaleUp() throws Exception {
        testScaleUpDelay(1);
    }

    // Test that the scale up request delay does expire after a while, and next scale up is delayed again
    @Test
    public void testScaleUpDelayReset() throws Exception {
        testScaleUpDelay(2);
    }

    private void testScaleUpDelay(int N) throws Exception {
        final AtomicBoolean scaleUpReceived = new AtomicBoolean();
        final AtomicBoolean scaleDownReceived = new AtomicBoolean();
        final Action1<AutoScaleAction> callback = new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction autoScaleAction) {
                switch (autoScaleAction.getType()) {
                    case Down:
                        scaleDownReceived.set(true);
                        break;
                    case Up:
                        scaleUpReceived.set(true);
                        break;
                }
            }
        };
        long scaleupDelaySecs = 2L;
        TaskScheduler scheduler = getScheduler(false, callback, scaleupDelaySecs, 0, rule1);
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes.put(hostAttrName, attribute);
        final List<VirtualMachineLease> leases = new ArrayList<>();
        for(int l=0; l<minIdle; l++)
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes));
        for(int i=0; i<coolDownSecs+1; i++) {
            if(i>0)
                leases.clear();
            final SchedulingResult result = scheduler.scheduleOnce(Collections.<TaskRequest>emptyList(), leases);
            Thread.sleep(1000);
        }
        Assert.assertFalse("Unexpected to scale DOWN", scaleDownReceived.get());
        Assert.assertFalse("Unexpected to scale UP", scaleUpReceived.get());
        for(int o=0; o<N; o++) {
            if(o==1)
                Thread.sleep((long)(2.5 * scaleupDelaySecs)*1000L); // delay next round by a while to reset previous delay
            for(int i=0; i<scaleupDelaySecs+2; i++) {
                List<TaskRequest> tasks = new ArrayList<>();
                if(i==0) {
                    tasks.add(TaskRequestProvider.getTaskRequest(1, 1000, 1));
                }
                final SchedulingResult result = scheduler.scheduleOnce(tasks, leases);
                final Map<String, VMAssignmentResult> resultMap = result.getResultMap();
                if(!tasks.isEmpty()) {
                    Assert.assertTrue(resultMap.size() == 1);
                    leases.add(LeaseProvider.getConsumedLease(resultMap.values().iterator().next()));
                }
                else
                    leases.clear();
                Thread.sleep(1000);
            }
            Assert.assertFalse("Unexpected to scale DOWN", scaleDownReceived.get());
            Assert.assertFalse("Unexpected to scale UP", scaleUpReceived.get());
        }
        // ensure normal scale up request happens after the delay
        scheduler.scheduleOnce(Collections.singletonList(TaskRequestProvider.getTaskRequest(1, 1000, 1)),
                Collections.<VirtualMachineLease>emptyList());
        Thread.sleep(1000);
        Assert.assertTrue("Expected to scale UP", scaleUpReceived.get());
    }

    // Test that a scale down action doesn't kick in for a very short duration breach of maxIdle
    @Test
    public void testDelayedAutoscaleDown() throws Exception {
        testScaleDownDelay(1);
    }

    @Test
    public void testScaleDownDelayReset() throws Exception {
        testScaleDownDelay(2);
    }

    private void testScaleDownDelay(int N) throws Exception {
        final AtomicBoolean scaleUpReceived = new AtomicBoolean();
        final AtomicBoolean scaleDownReceived = new AtomicBoolean();
        final Action1<AutoScaleAction> callback = new Action1<AutoScaleAction>() {
            @Override
            public void call(AutoScaleAction autoScaleAction) {
                switch (autoScaleAction.getType()) {
                    case Down:
                        scaleDownReceived.set(true);
                        break;
                    case Up:
                        scaleUpReceived.set(true);
                        break;
                }
            }
        };
        long scaleDownDelay=3;
        TaskScheduler scheduler = getScheduler(true, callback, 0, scaleDownDelay, rule1);
        List<VirtualMachineLease.Range> ports = new ArrayList<>();
        ports.add(new VirtualMachineLease.Range(1, 10));
        Map<String, Protos.Attribute> attributes = new HashMap<>();
        Protos.Attribute attribute = Protos.Attribute.newBuilder().setName(hostAttrName)
                .setType(Protos.Value.Type.TEXT)
                .setText(Protos.Value.Text.newBuilder().setValue(hostAttrVal1)).build();
        attributes.put(hostAttrName, attribute);
        final List<VirtualMachineLease> leases = new ArrayList<>();
        for(int l=0; l<maxIdle+2; l++)
            leases.add(LeaseProvider.getLeaseOffer("host"+l, cpus1, memory1, ports, attributes));
        List<TaskRequest> tasks = new ArrayList<>();
        for(int t=0; t<2; t++)
            tasks.add(TaskRequestProvider.getTaskRequest(cpus1, memory1, 1));
        final SchedulingResult result = scheduler.scheduleOnce(tasks, leases);
        final Map<String, VMAssignmentResult> resultMap = result.getResultMap();
        Assert.assertEquals(tasks.size(), resultMap.size());
        tasks.clear();
        leases.clear();
        Thread.sleep(coolDownSecs * 1000L + 500L);
        // mark completion of 1 task; add back one of the leases
        leases.add(resultMap.values().iterator().next().getLeasesUsed().iterator().next());
        // run scheduler for (scaleDownDelay-1) secs and ensure we didn't get scale down request
        for(int i=0; i<scaleDownDelay-1; i++) {
            scheduler.scheduleOnce(tasks, leases);
            if(i==0)
                leases.clear();
            Thread.sleep(1000);
        }
        Assert.assertFalse("Scale down not expected", scaleDownReceived.get());
        Assert.assertFalse("Scale up not expected", scaleUpReceived.get());
        for(int o=0; o<N; o++) {
            if(o==1) {
                Thread.sleep((long) (2.5 * scaleDownDelay) * 1000L);
                leases.add(LeaseProvider.getLeaseOffer("hostFoo", cpus1, memory1, ports, attributes));
            }
            else
                tasks.add(TaskRequestProvider.getTaskRequest(cpus1, memory1, 1));
            for (int i = 0; i < scaleDownDelay+1; i++) {
                final SchedulingResult result1 = scheduler.scheduleOnce(tasks, leases);
                leases.clear();
                if (!tasks.isEmpty()) {
                    Assert.assertEquals(tasks.size(), result1.getResultMap().size());
                    tasks.clear();
                }
                Thread.sleep(1000);
            }
            Assert.assertFalse("Scale down not expected", scaleDownReceived.get());
            Assert.assertFalse("Scale up not expected", scaleUpReceived.get());
        }
        // ensure normal scale down request happens after the delay
        for(int l=0; l<N; l++)
            leases.add(LeaseProvider.getLeaseOffer("host"+(100+l), cpus1, memory1, ports, attributes));
        SchedulingResult result1 = scheduler.scheduleOnce(Collections.<TaskRequest>emptyList(), leases);
        leases.clear();
        Thread.sleep(1000);
        Assert.assertFalse("Scale up not expected", scaleUpReceived.get());
        Assert.assertTrue("Scale down expected", scaleDownReceived.get());
    }
}
