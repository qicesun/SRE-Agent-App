package com.qicesun.sreagent.config;

public class SessionConfig {

    private boolean k8sEnabled;
    private String k8sNamespace;
    private String k8sWorkloadKind;
    private String k8sWorkloadName;
    private boolean gitlabEnabled;
    private String gitlabProjectId;
    private boolean jiraEnabled;
    private String jiraProjectKey;

    public boolean isK8sEnabled() {
        return k8sEnabled;
    }

    public void setK8sEnabled(boolean k8sEnabled) {
        this.k8sEnabled = k8sEnabled;
    }

    public String getK8sNamespace() {
        return k8sNamespace;
    }

    public void setK8sNamespace(String k8sNamespace) {
        this.k8sNamespace = k8sNamespace;
    }

    public String getK8sWorkloadKind() {
        return k8sWorkloadKind;
    }

    public void setK8sWorkloadKind(String k8sWorkloadKind) {
        this.k8sWorkloadKind = k8sWorkloadKind;
    }

    public String getK8sWorkloadName() {
        return k8sWorkloadName;
    }

    public void setK8sWorkloadName(String k8sWorkloadName) {
        this.k8sWorkloadName = k8sWorkloadName;
    }

    public boolean isGitlabEnabled() {
        return gitlabEnabled;
    }

    public void setGitlabEnabled(boolean gitlabEnabled) {
        this.gitlabEnabled = gitlabEnabled;
    }

    public String getGitlabProjectId() {
        return gitlabProjectId;
    }

    public void setGitlabProjectId(String gitlabProjectId) {
        this.gitlabProjectId = gitlabProjectId;
    }

    public boolean isJiraEnabled() {
        return jiraEnabled;
    }

    public void setJiraEnabled(boolean jiraEnabled) {
        this.jiraEnabled = jiraEnabled;
    }

    public String getJiraProjectKey() {
        return jiraProjectKey;
    }

    public void setJiraProjectKey(String jiraProjectKey) {
        this.jiraProjectKey = jiraProjectKey;
    }
}
