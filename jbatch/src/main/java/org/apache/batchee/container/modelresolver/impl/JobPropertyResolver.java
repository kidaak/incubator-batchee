/*
 * Copyright 2012 International Business Machines Corp.
 * 
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.apache.batchee.container.modelresolver.impl;

import org.apache.batchee.container.jsl.ExecutionElement;
import org.apache.batchee.container.modelresolver.PropertyResolverFactory;
import org.apache.batchee.jaxb.Decision;
import org.apache.batchee.jaxb.Flow;
import org.apache.batchee.jaxb.JSLJob;
import org.apache.batchee.jaxb.Listener;
import org.apache.batchee.jaxb.Split;
import org.apache.batchee.jaxb.Step;

import java.util.Properties;


public class JobPropertyResolver extends AbstractPropertyResolver<JSLJob> {

    public JobPropertyResolver(boolean isPartitionStep) {
        super(isPartitionStep);
    }

    /**
     * @param job            This method will modify the given job. If you need to hold on
     *                       to the original job you need to create a clone of the job
     *                       before passing it to this method.
     * @param submittedProps The job parameters associated with this job. null is valid if
     *                       no parameters are passed.
     * @param parentProps    Properties that are inherited from parent elements. Job is top
     *                       level element so it can have no parents, so this paramter is
     *                       currently ignored. Null is valid.
     * @return
     */
    public JSLJob substituteProperties(final JSLJob job, final Properties submittedProps, final Properties parentProps) {

        // resolve all the properties used in attributes and update the JAXB
        // model
        job.setId(this.replaceAllProperties(job.getId(), submittedProps, parentProps));
        job.setRestartable(this.replaceAllProperties(job.getRestartable(), submittedProps, parentProps));

        // Resolve all the properties defined for a job
        Properties currentProps = null;
        if (job.getProperties() != null) {
            currentProps = this.resolveElementProperties(job.getProperties().getPropertyList(), submittedProps, parentProps);
        }

        // Resolve Listener properties, this is list of listeners List<Listener>
        if (job.getListeners() != null) {
            for (final Listener listener : job.getListeners().getListenerList()) {
                PropertyResolverFactory.createListenerPropertyResolver(this.isPartitionedStep).substituteProperties(listener, submittedProps, currentProps);
            }
        }

        for (final ExecutionElement next : job.getExecutionElements()) {
            if (next instanceof Step) {
                PropertyResolverFactory.createStepPropertyResolver(this.isPartitionedStep).substituteProperties((Step) next, submittedProps, currentProps);
            } else if (next instanceof Decision) {
                PropertyResolverFactory.createDecisionPropertyResolver(this.isPartitionedStep).substituteProperties((Decision) next, submittedProps, currentProps);
            } else if (next instanceof Split) {
                PropertyResolverFactory.createSplitPropertyResolver(this.isPartitionedStep).substituteProperties((Split) next, submittedProps, currentProps);
            } else if (next instanceof Flow) {
                PropertyResolverFactory.createFlowPropertyResolver(this.isPartitionedStep).substituteProperties((Flow) next, submittedProps, currentProps);
            }
        }


        return job;

    }


}
