/*
 * Copyright 2013 Twitter, Inc.
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
package com.twitter.aurora.scheduler.state;

import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import com.google.common.base.Optional;

import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.TaskInfo;

import com.twitter.aurora.scheduler.MesosTaskFactory;
import com.twitter.aurora.scheduler.ResourceSlot;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.Resources;
import com.twitter.aurora.scheduler.filter.SchedulingFilter;
import com.twitter.aurora.scheduler.filter.SchedulingFilter.Veto;
import com.twitter.aurora.scheduler.storage.entities.IAssignedTask;
import com.twitter.aurora.scheduler.storage.entities.IScheduledTask;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Responsible for matching a task against an offer.
 */
public interface TaskAssigner {

  /**
   * Tries to match a task against an offer.  If a match is found, the assigner should
   * make the appropriate changes to the task and provide a non-empty result.
   *
   * @param offer The resource offer.
   * @param task The task to match against and optionally assign.
   * @return Instructions for launching the task if matching and assignment were successful.
   */
  Optional<TaskInfo> maybeAssign(Offer offer, IScheduledTask task);

  class TaskAssignerImpl implements TaskAssigner {
    private static final Logger LOG = Logger.getLogger(TaskAssignerImpl.class.getName());

    private final StateManager stateManager;
    private final SchedulingFilter filter;
    private final MesosTaskFactory taskFactory;

    @Inject
    public TaskAssignerImpl(
        StateManager stateManager,
        SchedulingFilter filter,
        MesosTaskFactory taskFactory) {

      this.stateManager = checkNotNull(stateManager);
      this.filter = checkNotNull(filter);
      this.taskFactory = checkNotNull(taskFactory);
    }

    private TaskInfo assign(Offer offer, IScheduledTask task) {
      String host = offer.getHostname();
      Set<Integer> selectedPorts =
          Resources.getPorts(offer, task.getAssignedTask().getTask().getRequestedPorts().size());
      IAssignedTask assigned = stateManager.assignTask(
          Tasks.id(task),
          host,
          offer.getSlaveId(),
          selectedPorts);
      LOG.info(String.format("Offer on slave %s (id %s) is being assigned task for %s.",
          host, offer.getSlaveId(), Tasks.id(task)));
      return taskFactory.createFrom(assigned, offer.getSlaveId());
    }

    @Override
    public Optional<TaskInfo> maybeAssign(Offer offer, IScheduledTask task) {
      Set<Veto> vetoes = filter.filter(
          ResourceSlot.from(offer),
          offer.getHostname(),
          task.getAssignedTask().getTask(),
          Tasks.id(task));
      if (vetoes.isEmpty()) {
        return Optional.of(assign(offer, task));
      } else {
        LOG.fine("Slave " + offer.getHostname() + " vetoed task " + Tasks.id(task)
            + ": " + vetoes);
        return Optional.absent();
      }
    }
  }
}