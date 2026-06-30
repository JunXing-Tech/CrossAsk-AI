package com.crossask.eval.model;

import java.util.List;

/**
 * 对应 eval-core.yaml 中一条评测用例。
 */
public class EvalCase {

    private String id;
    private String question;
    private String category;
    private List<String> expectedTools;
    private List<String> expectedSourceUrls;
    private List<String> expectedItemIds;
    private List<String> expectedKeywords;
    private String expectedAnswerLang;
    private String notes;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public List<String> getExpectedTools() { return expectedTools; }
    public void setExpectedTools(List<String> expectedTools) { this.expectedTools = expectedTools; }

    public List<String> getExpectedSourceUrls() { return expectedSourceUrls; }
    public void setExpectedSourceUrls(List<String> expectedSourceUrls) { this.expectedSourceUrls = expectedSourceUrls; }

    public List<String> getExpectedItemIds() { return expectedItemIds; }
    public void setExpectedItemIds(List<String> expectedItemIds) { this.expectedItemIds = expectedItemIds; }

    public List<String> getExpectedKeywords() { return expectedKeywords; }
    public void setExpectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; }

    public String getExpectedAnswerLang() { return expectedAnswerLang; }
    public void setExpectedAnswerLang(String expectedAnswerLang) { this.expectedAnswerLang = expectedAnswerLang; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
