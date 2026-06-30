package com.crossask.api.rag;

import com.crossask.common.model.Source;
import com.crossask.common.model.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v0.7 RAG 文档检索工具。
 */
@Component
public class RagSearchTool {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    private final RagSearchService ragSearchService;

    public RagSearchTool(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    @Tool(description =
            "Search internal help center documents (eBay & USPS policy / shipping / returns). " +
            "Use this for questions about policies, shipping times, return rules, customs etc. " +
            "Do NOT use this for product price/stock/seller queries.")
    public List<Source> searchDocs(
            @ToolParam(description = "User's question in original wording") String query) {
        try {
            List<Source> result = ragSearchService.search(query);
            ToolCallContext.addSources(result);
            log.info("searchDocs: query={}, -> {} sources", query, result.size());
            return result;
        } catch (Exception e) {
            log.error("searchDocs 异常", e);
            return List.of();
        }
    }
}