package com.tataplay.issueresolver.config;

import com.tataplay.issueresolver.tool.AgentToolsFacade;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureOpenAiConfig {

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient issueResolverChatClient(ChatModel chatModel, AgentToolsFacade agentToolsFacade) {
        return ChatClient.builder(chatModel)
                .defaultTools(agentToolsFacade)
                .build();
    }
}
