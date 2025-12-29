package com.qicesun.sreagent.tools.k8s;

import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.SessionContextHolder;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class KubernetesTool {

    private static final Logger log = LoggerFactory.getLogger(KubernetesTool.class);

    private final KubernetesClient client;
    private AgentEventStore eventStore;
    private SessionConfigStore configStore;

    public KubernetesTool(KubernetesClient client) {
        this.client = client;
    }

    @Autowired
    public void setEventStore(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Autowired
    public void setConfigStore(SessionConfigStore configStore) {
        this.configStore = configStore;
    }

    @Tool
    public String listPods(@P("The namespace to search, e.g., 'default'") String namespace) {
        long startNanos = System.nanoTime();
        SessionConfig sessionConfig = resolveSessionConfig();
        if (sessionConfig != null) {
            if (!sessionConfig.isK8sEnabled()) {
                return "Kubernetes is disabled for this session.";
            }
            String scopedNamespace = sessionConfig.getK8sNamespace();
            String scopedKind = sessionConfig.getK8sWorkloadKind();
            String scopedWorkload = sessionConfig.getK8sWorkloadName();
            if (isBlank(scopedNamespace) || isBlank(scopedKind) || isBlank(scopedWorkload)) {
                return "Kubernetes scope is not configured for this session.";
            }
            List<Pod> pods = listPodsForWorkload(scopedNamespace, scopedKind, scopedWorkload);
            if (pods.isEmpty()) {
                log.info("listPods namespace={} workload={}/{} count=0 elapsedMs={}",
                        scopedNamespace, scopedKind, scopedWorkload, (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("k8s.listPods",
                        "List pods for workload=" + scopedKind + "/" + scopedWorkload + " in namespace=" + scopedNamespace
                                + " (count=0)");
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                        + "\nNo pods found for workload: " + scopedKind + "/" + scopedWorkload + " in namespace: "
                        + scopedNamespace + ".";
            }

            List<PodRow> rows = new ArrayList<>();
            for (Pod pod : pods) {
                String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";
                String status = pod.getStatus() != null && pod.getStatus().getPhase() != null
                        ? pod.getStatus().getPhase()
                        : "Unknown";
                int restarts = totalRestarts(pod.getStatus() != null ? pod.getStatus().getContainerStatuses() : null);
                String age = formatAge(pod.getMetadata() != null ? pod.getMetadata().getCreationTimestamp() : null);
                rows.add(new PodRow(name, status, restarts, age));
            }

            log.info("listPods namespace={} workload={}/{} count={} elapsedMs={}",
                    scopedNamespace, scopedKind, scopedWorkload, rows.size(), (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.listPods",
                    "List pods for workload=" + scopedKind + "/" + scopedWorkload + " in namespace=" + scopedNamespace
                            + " (count=" + rows.size() + ")");
            return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace) + "\n" + formatPodTable(rows);
        }

        if (namespace == null || namespace.trim().isEmpty()) {
            return "Namespace is required.";
        }

        PodList podList = client.pods().inNamespace(namespace).list();
        if (podList == null || podList.getItems() == null || podList.getItems().isEmpty()) {
            log.info("listPods namespace={} count=0 elapsedMs={}", namespace,
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.listPods", "List pods in namespace=" + namespace + " (count=0)");
            return "No pods found in namespace: " + namespace + ".";
        }

        List<PodRow> rows = new ArrayList<>();
        for (Pod pod : podList.getItems()) {
            String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";
            String status = pod.getStatus() != null && pod.getStatus().getPhase() != null
                    ? pod.getStatus().getPhase()
                    : "Unknown";
            int restarts = totalRestarts(pod.getStatus() != null ? pod.getStatus().getContainerStatuses() : null);
            String age = formatAge(pod.getMetadata() != null ? pod.getMetadata().getCreationTimestamp() : null);
            rows.add(new PodRow(name, status, restarts, age));
        }

        log.info("listPods namespace={} count={} elapsedMs={}", namespace, rows.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
        recordEvent("k8s.listPods", "List pods in namespace=" + namespace + " (count=" + rows.size() + ")");
        return formatPodTable(rows);
    }

    @Tool
    public String getPodLogs(
            @P("Namespace") String namespace,
            @P("Pod Name") String podName,
            @P("Number of lines (default 20)") int tailLines) {
        long startNanos = System.nanoTime();
        int effectiveTailLines = tailLines > 0 ? tailLines : 20;
        SessionConfig sessionConfig = resolveSessionConfig();
        if (sessionConfig != null) {
            if (!sessionConfig.isK8sEnabled()) {
                return "Kubernetes is disabled for this session.";
            }
            String scopedNamespace = sessionConfig.getK8sNamespace();
            String scopedKind = sessionConfig.getK8sWorkloadKind();
            String scopedWorkload = sessionConfig.getK8sWorkloadName();
            if (isBlank(scopedNamespace) || isBlank(scopedKind) || isBlank(scopedWorkload)) {
                return "Kubernetes scope is not configured for this session.";
            }
            List<Pod> pods = listPodsForWorkload(scopedNamespace, scopedKind, scopedWorkload);
            if (pods.isEmpty()) {
                log.info("getPodLogs namespace={} workload={}/{} result=no_pods elapsedMs={}",
                        scopedNamespace, scopedKind, scopedWorkload, (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("k8s.getPodLogs",
                        "No pods for workload=" + scopedKind + "/" + scopedWorkload + " in namespace=" + scopedNamespace);
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                        + "\nNo pods found for workload: " + scopedKind + "/" + scopedWorkload + " in namespace: "
                        + scopedNamespace + ".";
            }

            String requestedPod = podName == null ? "" : podName.trim();
            if (requestedPod.isEmpty()) {
                List<Pod> selected = selectPodsForLogs(pods, 3);
                StringBuilder sb = new StringBuilder();
                sb.append(buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)).append("\n");
                for (Pod pod : selected) {
                    String name = podName(pod);
                    sb.append("=== Pod: ").append(name).append(" ===").append("\n");
                    try {
                        String logs = client.pods()
                                .inNamespace(scopedNamespace)
                                .withName(name)
                                .tailingLines(effectiveTailLines)
                                .getLog();
                        if (logs == null || logs.isBlank()) {
                            sb.append("No logs found for pod: ").append(name).append(".").append("\n");
                        } else {
                            sb.append(logs.trim()).append("\n");
                        }
                    } catch (Exception e) {
                        sb.append("Error retrieving logs for pod ").append(name).append(": ")
                                .append(e.getMessage()).append("\n");
                    }
                }
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("getPodLogs namespace={} workload={}/{} pods={} tailLines={} elapsedMs={}",
                        scopedNamespace, scopedKind, scopedWorkload, selected.size(), effectiveTailLines, elapsedMs);
                recordEvent("k8s.getPodLogs",
                        "Fetched logs for workload=" + scopedKind + "/" + scopedWorkload + " (pods=" + selected.size()
                                + ", lines=" + effectiveTailLines + ")");
                return sb.toString().trim();
            }

            boolean withinScope = pods.stream().anyMatch(pod -> requestedPod.equals(podName(pod)));
            if (!withinScope) {
                recordEvent("k8s.getPodLogs",
                        "Refused logs for pod=" + requestedPod + " (outside scope workload=" + scopedKind + "/"
                                + scopedWorkload + ")");
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                        + "\nPod is outside selected workload scope: " + requestedPod + ".";
            }

            try {
                String logs = client.pods()
                        .inNamespace(scopedNamespace)
                        .withName(requestedPod)
                        .tailingLines(effectiveTailLines)
                        .getLog();
                if (logs == null || logs.isBlank()) {
                    log.info("getPodLogs namespace={} pod={} tailLines={} result=empty elapsedMs={}",
                            scopedNamespace, requestedPod, effectiveTailLines, (System.nanoTime() - startNanos) / 1_000_000);
                    recordEvent("k8s.getPodLogs",
                            "No logs for pod=" + requestedPod + " in namespace=" + scopedNamespace);
                    return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                            + "\nNo logs found for pod: " + requestedPod + " in namespace: " + scopedNamespace + ".";
                }
                log.info("getPodLogs namespace={} pod={} tailLines={} length={} elapsedMs={}",
                        scopedNamespace, requestedPod, effectiveTailLines, logs.length(),
                        (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("k8s.getPodLogs",
                        "Fetched logs for pod=" + requestedPod + " (lines=" + effectiveTailLines + ", length="
                                + logs.length() + ")");
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace) + "\n" + logs;
            } catch (Exception e) {
                log.warn("getPodLogs namespace={} pod={} tailLines={} error={}",
                        scopedNamespace, requestedPod, effectiveTailLines, e.getMessage());
                recordEvent("k8s.getPodLogs",
                        "Failed to fetch logs for pod=" + requestedPod + " error=" + e.getMessage());
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                        + "\nError retrieving logs: " + e.getMessage();
            }
        }

        try {
            String logs = client.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .tailingLines(effectiveTailLines)
                    .getLog();
            if (logs == null || logs.isBlank()) {
                log.info("getPodLogs namespace={} pod={} tailLines={} result=empty elapsedMs={}",
                        namespace, podName, effectiveTailLines, (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("k8s.getPodLogs",
                        "No logs for pod=" + podName + " in namespace=" + namespace);
                return "No logs found for pod: " + podName + " in namespace: " + namespace + ".";
            }
            log.info("getPodLogs namespace={} pod={} tailLines={} length={} elapsedMs={}",
                    namespace, podName, effectiveTailLines, logs.length(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.getPodLogs",
                    "Fetched logs for pod=" + podName + " (lines=" + effectiveTailLines + ", length=" + logs.length() + ")");
            return logs;
        } catch (Exception e) {
            log.warn("getPodLogs namespace={} pod={} tailLines={} error={}",
                    namespace, podName, effectiveTailLines, e.getMessage());
            recordEvent("k8s.getPodLogs",
                    "Failed to fetch logs for pod=" + podName + " error=" + e.getMessage());
            return "Error retrieving logs: " + e.getMessage();
        }
    }

    @Tool
    public String getDeploymentInfo(
            @P("Namespace") String namespace,
            @P("Deployment Name") String deploymentName) {
        long startNanos = System.nanoTime();
        SessionConfig sessionConfig = resolveSessionConfig();
        if (sessionConfig != null) {
            if (!sessionConfig.isK8sEnabled()) {
                return "Kubernetes is disabled for this session.";
            }
            String scopedNamespace = sessionConfig.getK8sNamespace();
            String scopedKind = sessionConfig.getK8sWorkloadKind();
            String scopedWorkload = sessionConfig.getK8sWorkloadName();
            if (isBlank(scopedNamespace) || isBlank(scopedKind) || isBlank(scopedWorkload)) {
                return "Kubernetes scope is not configured for this session.";
            }
            String requestedNamespace = namespace == null ? "" : namespace.trim();
            if (!requestedNamespace.isEmpty() && !requestedNamespace.equals(scopedNamespace)) {
                return "This session is locked to namespace: " + scopedNamespace + ".";
            }
            String requestedWorkload = deploymentName == null ? "" : deploymentName.trim();
            if (!requestedWorkload.isEmpty() && !requestedWorkload.equals(scopedWorkload)) {
                return "This session is locked to workload: " + scopedKind + "/" + scopedWorkload + ".";
            }
            if (scopedKind.equalsIgnoreCase("StatefulSet")) {
                return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                        + "\n"
                        + statefulSetInfo(scopedNamespace, scopedWorkload, startNanos);
            }
            return buildScopeNotice(scopedNamespace, scopedKind, scopedWorkload, namespace)
                    + "\n"
                    + deploymentInfo(scopedNamespace, scopedWorkload, startNanos);
        }

        return deploymentInfo(namespace, deploymentName, startNanos);
    }

    @Tool
    public String restartDeployment(
            @P("Namespace") String namespace,
            @P("Deployment Name") String deploymentName) {
        long startNanos = System.nanoTime();
        SessionConfig sessionConfig = resolveSessionConfig();
        if (sessionConfig != null) {
            if (!sessionConfig.isK8sEnabled()) {
                return "Kubernetes is disabled for this session.";
            }
            String scopedNamespace = sessionConfig.getK8sNamespace();
            String scopedKind = sessionConfig.getK8sWorkloadKind();
            String scopedWorkload = sessionConfig.getK8sWorkloadName();
            if (isBlank(scopedNamespace) || isBlank(scopedKind) || isBlank(scopedWorkload)) {
                return "Kubernetes scope is not configured for this session.";
            }
            if (scopedKind.equalsIgnoreCase("StatefulSet")) {
                return "Selected workload is StatefulSet; use restartStatefulSet.";
            }
            String requestedNamespace = namespace == null ? "" : namespace.trim();
            if (!requestedNamespace.isEmpty() && !requestedNamespace.equals(scopedNamespace)) {
                return "This session is locked to namespace: " + scopedNamespace + ".";
            }
            String requestedWorkload = deploymentName == null ? "" : deploymentName.trim();
            if (!requestedWorkload.isEmpty() && !requestedWorkload.equals(scopedWorkload)) {
                return "This session is locked to workload: " + scopedKind + "/" + scopedWorkload + ".";
            }
            return restartDeploymentInternal(scopedNamespace, scopedWorkload, startNanos);
        }

        try {
            client.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName)
                    .rolling()
                    .restart();
            log.info("restartDeployment namespace={} deployment={} elapsedMs={}",
                    namespace, deploymentName, (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.restartDeployment",
                    "Rolling restart triggered for deployment=" + deploymentName + " in namespace=" + namespace);
            return "Rolling restart triggered successfully for deployment: " + deploymentName + ".";
        } catch (Exception e) {
            log.warn("restartDeployment namespace={} deployment={} error={}",
                    namespace, deploymentName, e.getMessage());
            recordEvent("k8s.restartDeployment",
                    "Failed to restart deployment=" + deploymentName + " error=" + e.getMessage());
            return "Error restarting deployment: " + e.getMessage();
        }
    }

    @Tool
    public String restartStatefulSet(
            @P("Namespace") String namespace,
            @P("StatefulSet Name") String statefulSetName) {
        long startNanos = System.nanoTime();
        SessionConfig sessionConfig = resolveSessionConfig();
        if (sessionConfig != null) {
            if (!sessionConfig.isK8sEnabled()) {
                return "Kubernetes is disabled for this session.";
            }
            String scopedNamespace = sessionConfig.getK8sNamespace();
            String scopedKind = sessionConfig.getK8sWorkloadKind();
            String scopedWorkload = sessionConfig.getK8sWorkloadName();
            if (isBlank(scopedNamespace) || isBlank(scopedKind) || isBlank(scopedWorkload)) {
                return "Kubernetes scope is not configured for this session.";
            }
            if (scopedKind.equalsIgnoreCase("Deployment")) {
                return "Selected workload is Deployment; use restartDeployment.";
            }
            String requestedNamespace = namespace == null ? "" : namespace.trim();
            if (!requestedNamespace.isEmpty() && !requestedNamespace.equals(scopedNamespace)) {
                return "This session is locked to namespace: " + scopedNamespace + ".";
            }
            String requestedWorkload = statefulSetName == null ? "" : statefulSetName.trim();
            if (!requestedWorkload.isEmpty() && !requestedWorkload.equals(scopedWorkload)) {
                return "This session is locked to workload: " + scopedKind + "/" + scopedWorkload + ".";
            }
            return restartStatefulSetInternal(scopedNamespace, scopedWorkload, startNanos);
        }

        return restartStatefulSetInternal(namespace, statefulSetName, startNanos);
    }

    private String deploymentInfo(String namespace, String deploymentName, long startNanos) {
        String ns = namespace == null ? "" : namespace.trim();
        String name = deploymentName == null ? "" : deploymentName.trim();
        if (ns.isEmpty() || name.isEmpty()) {
            return "Namespace and deployment name are required.";
        }

        Deployment deployment = client.apps()
                .deployments()
                .inNamespace(ns)
                .withName(name)
                .get();
        if (deployment == null) {
            log.info("getDeploymentInfo namespace={} deployment={} result=not_found elapsedMs={}",
                    ns, name, (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.getDeploymentInfo", "Deployment not found: " + name + " in namespace=" + ns);
            return "Deployment not found: " + name + ".";
        }

        Integer desiredReplicas = deployment.getSpec() != null ? deployment.getSpec().getReplicas() : null;
        Integer readyReplicas = deployment.getStatus() != null ? deployment.getStatus().getReadyReplicas() : null;
        String readyValue = readyReplicas != null ? readyReplicas.toString() : "0";
        String desiredValue = desiredReplicas != null ? desiredReplicas.toString() : "0";
        String replicas = String.format("Replicas: %s/%s", readyValue, desiredValue);

        List<String> images = new ArrayList<>();
        if (deployment.getSpec() != null
                && deployment.getSpec().getTemplate() != null
                && deployment.getSpec().getTemplate().getSpec() != null
                && deployment.getSpec().getTemplate().getSpec().getContainers() != null) {
            for (Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
                if (container.getImage() != null) {
                    images.add(container.getImage());
                }
            }
        }
        String imageSummary = images.isEmpty()
                ? "Images: none"
                : "Images: " + String.join(", ", images);

        List<String> conditions = new ArrayList<>();
        if (deployment.getStatus() != null && deployment.getStatus().getConditions() != null) {
            for (DeploymentCondition condition : deployment.getStatus().getConditions()) {
                if (condition != null) {
                    String type = condition.getType() != null ? condition.getType() : "Unknown";
                    String status = condition.getStatus() != null ? condition.getStatus() : "Unknown";
                    conditions.add(type + "=" + status);
                }
            }
        }
        String conditionSummary = conditions.isEmpty()
                ? "Conditions: none"
                : "Conditions: " + String.join(", ", conditions);

        log.info("getDeploymentInfo namespace={} deployment={} elapsedMs={}",
                ns, name, (System.nanoTime() - startNanos) / 1_000_000);
        recordEvent("k8s.getDeploymentInfo",
                "Fetched deployment info for " + name + " (" + readyValue + "/" + desiredValue + ")");
        return replicas + "\n" + imageSummary + "\n" + conditionSummary;
    }

    private String statefulSetInfo(String namespace, String statefulSetName, long startNanos) {
        String ns = namespace == null ? "" : namespace.trim();
        String name = statefulSetName == null ? "" : statefulSetName.trim();
        if (ns.isEmpty() || name.isEmpty()) {
            return "Namespace and statefulset name are required.";
        }

        StatefulSet statefulSet = client.apps()
                .statefulSets()
                .inNamespace(ns)
                .withName(name)
                .get();
        if (statefulSet == null) {
            log.info("getStatefulSetInfo namespace={} statefulSet={} result=not_found elapsedMs={}",
                    ns, name, (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.getStatefulSetInfo", "StatefulSet not found: " + name + " in namespace=" + ns);
            return "StatefulSet not found: " + name + ".";
        }

        Integer desiredReplicas = statefulSet.getSpec() != null ? statefulSet.getSpec().getReplicas() : null;
        Integer readyReplicas = statefulSet.getStatus() != null ? statefulSet.getStatus().getReadyReplicas() : null;
        String readyValue = readyReplicas != null ? readyReplicas.toString() : "0";
        String desiredValue = desiredReplicas != null ? desiredReplicas.toString() : "0";
        String replicas = String.format("Replicas: %s/%s", readyValue, desiredValue);

        List<String> images = new ArrayList<>();
        if (statefulSet.getSpec() != null
                && statefulSet.getSpec().getTemplate() != null
                && statefulSet.getSpec().getTemplate().getSpec() != null
                && statefulSet.getSpec().getTemplate().getSpec().getContainers() != null) {
            for (Container container : statefulSet.getSpec().getTemplate().getSpec().getContainers()) {
                if (container.getImage() != null) {
                    images.add(container.getImage());
                }
            }
        }
        String imageSummary = images.isEmpty()
                ? "Images: none"
                : "Images: " + String.join(", ", images);

        List<String> conditions = new ArrayList<>();
        if (statefulSet.getStatus() != null && statefulSet.getStatus().getConditions() != null) {
            for (StatefulSetCondition condition : statefulSet.getStatus().getConditions()) {
                if (condition != null) {
                    String type = condition.getType() != null ? condition.getType() : "Unknown";
                    String status = condition.getStatus() != null ? condition.getStatus() : "Unknown";
                    conditions.add(type + "=" + status);
                }
            }
        }
        String conditionSummary = conditions.isEmpty()
                ? "Conditions: none"
                : "Conditions: " + String.join(", ", conditions);

        log.info("getStatefulSetInfo namespace={} statefulSet={} elapsedMs={}",
                ns, name, (System.nanoTime() - startNanos) / 1_000_000);
        recordEvent("k8s.getStatefulSetInfo",
                "Fetched statefulset info for " + name + " (" + readyValue + "/" + desiredValue + ")");
        return replicas + "\n" + imageSummary + "\n" + conditionSummary;
    }

    private String restartDeploymentInternal(String namespace, String deploymentName, long startNanos) {
        String ns = namespace == null ? "" : namespace.trim();
        String name = deploymentName == null ? "" : deploymentName.trim();
        if (ns.isEmpty() || name.isEmpty()) {
            return "Namespace and deployment name are required.";
        }
        try {
            client.apps()
                    .deployments()
                    .inNamespace(ns)
                    .withName(name)
                    .rolling()
                    .restart();
            log.info("restartDeployment namespace={} deployment={} elapsedMs={}",
                    ns, name, (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.restartDeployment",
                    "Rolling restart triggered for deployment=" + name + " in namespace=" + ns);
            return "Rolling restart triggered successfully for deployment: " + name + ".";
        } catch (Exception e) {
            log.warn("restartDeployment namespace={} deployment={} error={}", ns, name, e.getMessage());
            recordEvent("k8s.restartDeployment",
                    "Failed to restart deployment=" + name + " error=" + e.getMessage());
            return "Error restarting deployment: " + e.getMessage();
        }
    }

    private String restartStatefulSetInternal(String namespace, String statefulSetName, long startNanos) {
        String ns = namespace == null ? "" : namespace.trim();
        String name = statefulSetName == null ? "" : statefulSetName.trim();
        if (ns.isEmpty() || name.isEmpty()) {
            return "Namespace and statefulset name are required.";
        }
        try {
            client.apps()
                    .statefulSets()
                    .inNamespace(ns)
                    .withName(name)
                    .rolling()
                    .restart();
            log.info("restartStatefulSet namespace={} statefulSet={} elapsedMs={}",
                    ns, name, (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("k8s.restartStatefulSet",
                    "Rolling restart triggered for statefulSet=" + name + " in namespace=" + ns);
            return "Rolling restart triggered successfully for statefulset: " + name + ".";
        } catch (Exception e) {
            log.warn("restartStatefulSet namespace={} statefulSet={} error={}", ns, name, e.getMessage());
            recordEvent("k8s.restartStatefulSet",
                    "Failed to restart statefulSet=" + name + " error=" + e.getMessage());
            return "Error restarting statefulset: " + e.getMessage();
        }
    }

    private SessionConfig resolveSessionConfig() {
        if (configStore == null) {
            return null;
        }
        String sessionId = SessionContextHolder.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return configStore.get(sessionId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String buildScopeNotice(
            String namespace,
            String kind,
            String workload,
            String requestedNamespace) {
        String ns = namespace == null ? "" : namespace.trim();
        String wkKind = kind == null ? "" : kind.trim();
        String wkName = workload == null ? "" : workload.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Scope: namespace=").append(ns).append(", workload=").append(wkKind).append("/").append(wkName);
        String requested = requestedNamespace == null ? "" : requestedNamespace.trim();
        if (!requested.isEmpty() && !requested.equals(ns)) {
            sb.append(" (ignored namespace=").append(requested).append(")");
        }
        return sb.toString();
    }

    private List<Pod> listPodsForWorkload(String namespace, String kind, String workloadName) {
        String ns = namespace == null ? "" : namespace.trim();
        String wkKind = kind == null ? "" : kind.trim();
        String name = workloadName == null ? "" : workloadName.trim();
        if (ns.isEmpty() || wkKind.isEmpty() || name.isEmpty()) {
            return List.of();
        }

        Map<String, String> selectorLabels = null;
        if (wkKind.equalsIgnoreCase("Deployment")) {
            Deployment deployment = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (deployment != null
                    && deployment.getSpec() != null
                    && deployment.getSpec().getSelector() != null) {
                selectorLabels = deployment.getSpec().getSelector().getMatchLabels();
            }
        } else if (wkKind.equalsIgnoreCase("StatefulSet")) {
            StatefulSet statefulSet = client.apps().statefulSets().inNamespace(ns).withName(name).get();
            if (statefulSet != null
                    && statefulSet.getSpec() != null
                    && statefulSet.getSpec().getSelector() != null) {
                selectorLabels = statefulSet.getSpec().getSelector().getMatchLabels();
            }
        } else {
            return List.of();
        }

        PodList podList;
        if (selectorLabels != null && !selectorLabels.isEmpty()) {
            podList = client.pods().inNamespace(ns).withLabels(selectorLabels).list();
        } else {
            podList = client.pods().inNamespace(ns).list();
        }
        return filterPodsByPrefix(podList, name + "-");
    }

    private static List<Pod> filterPodsByPrefix(PodList podList, String prefix) {
        if (podList == null || podList.getItems() == null || podList.getItems().isEmpty()) {
            return List.of();
        }
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(podList.getItems());
        }
        List<Pod> filtered = new ArrayList<>();
        for (Pod pod : podList.getItems()) {
            String name = podName(pod);
            if (name.startsWith(prefix)) {
                filtered.add(pod);
            }
        }
        if (filtered.isEmpty()) {
            return new ArrayList<>(podList.getItems());
        }
        return filtered;
    }

    private List<Pod> selectPodsForLogs(List<Pod> pods, int limit) {
        if (pods == null || pods.isEmpty()) {
            return List.of();
        }
        int max = limit > 0 ? limit : 1;
        List<Pod> copy = new ArrayList<>(pods);
        copy.sort(Comparator
                .comparingInt((Pod pod) -> totalRestarts(pod.getStatus() != null ? pod.getStatus().getContainerStatuses() : null))
                .reversed()
                .thenComparing((Pod pod) -> parseTimestamp(pod.getMetadata() != null ? pod.getMetadata().getCreationTimestamp() : null),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(KubernetesTool::podName, String.CASE_INSENSITIVE_ORDER));
        if (copy.size() <= max) {
            return copy;
        }
        return new ArrayList<>(copy.subList(0, max));
    }

    private static OffsetDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String podName(Pod pod) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getName() == null) {
            return "unknown";
        }
        return pod.getMetadata().getName();
    }

    private void recordEvent(String source, String message) {
        if (eventStore == null) {
            return;
        }
        String sessionId = SessionContextHolder.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        eventStore.record(sessionId, source, message);
    }

    private int totalRestarts(List<ContainerStatus> statuses) {
        if (statuses == null) {
            return 0;
        }
        int sum = 0;
        for (ContainerStatus status : statuses) {
            if (status != null && status.getRestartCount() != null) {
                sum += status.getRestartCount();
            }
        }
        return sum;
    }

    private String formatAge(String creationTimestamp) {
        if (creationTimestamp == null || creationTimestamp.isEmpty()) {
            return "unknown";
        }
        try {
            OffsetDateTime createdAt = OffsetDateTime.parse(creationTimestamp);
            Duration duration = Duration.between(createdAt, OffsetDateTime.now());
            return humanizeDuration(duration);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String humanizeDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        if (days > 0) {
            return days + "d";
        }
        if (hours > 0) {
            return hours + "h";
        }
        return minutes + "m";
    }

    private String formatPodTable(List<PodRow> rows) {
        int nameWidth = "Name".length();
        int statusWidth = "Status".length();
        int restartsWidth = "Restarts".length();
        int ageWidth = "Age".length();

        for (PodRow row : rows) {
            nameWidth = Math.max(nameWidth, row.name.length());
            statusWidth = Math.max(statusWidth, row.status.length());
            restartsWidth = Math.max(restartsWidth, String.valueOf(row.restarts).length());
            ageWidth = Math.max(ageWidth, row.age.length());
        }

        StringBuilder builder = new StringBuilder();
        builder.append(padRight("Name", nameWidth))
                .append(" | ")
                .append(padRight("Status", statusWidth))
                .append(" | ")
                .append(padRight("Restarts", restartsWidth))
                .append(" | ")
                .append(padRight("Age", ageWidth))
                .append("\n");

        for (PodRow row : rows) {
            builder.append(padRight(row.name, nameWidth))
                    .append(" | ")
                    .append(padRight(row.status, statusWidth))
                    .append(" | ")
                    .append(padRight(String.valueOf(row.restarts), restartsWidth))
                    .append(" | ")
                    .append(padRight(row.age, ageWidth))
                    .append("\n");
        }

        return builder.toString().trim();
    }

    private String padRight(String value, int width) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= width) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static class PodRow {
        private final String name;
        private final String status;
        private final int restarts;
        private final String age;

        private PodRow(String name, String status, int restarts, String age) {
            this.name = Objects.requireNonNullElse(name, "unknown");
            this.status = Objects.requireNonNullElse(status, "Unknown");
            this.restarts = restarts;
            this.age = Objects.requireNonNullElse(age, "unknown");
        }
    }
}
