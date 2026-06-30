package com.crossask.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * v0.8 Eval 配置（crossask.eval.*）。
 */
@ConfigurationProperties(prefix = "crossask.eval")
public class EvalProperties {

    private String apiUrl = "http://localhost:8080/ask";
    private String evalSet = "eval-sets/eval-core.yaml";
    private String reportDir = "eval-reports";
    private int timeoutSeconds = 90;
    /** 被测 api 的配置快照（手工填入，写进报告头部供版本对比）。 */
    private String apiConfigSnapshot = "hybrid=true, rerank=true, retrieveTopK=15, rerankTopK=5";

    private Judge judge = new Judge();

    public static class Judge {
        private boolean enabled = false;
        private String model = "qwen-plus";
        private String apiKey = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getEvalSet() { return evalSet; }
    public void setEvalSet(String evalSet) { this.evalSet = evalSet; }

    public String getReportDir() { return reportDir; }
    public void setReportDir(String reportDir) { this.reportDir = reportDir; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getApiConfigSnapshot() { return apiConfigSnapshot; }
    public void setApiConfigSnapshot(String apiConfigSnapshot) { this.apiConfigSnapshot = apiConfigSnapshot; }

    public Judge getJudge() { return judge; }
    public void setJudge(Judge judge) { this.judge = judge; }
}
