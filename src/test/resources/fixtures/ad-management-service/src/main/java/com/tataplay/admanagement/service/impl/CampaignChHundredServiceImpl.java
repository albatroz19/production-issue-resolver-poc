package com.tataplay.admanagement.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class CampaignChHundredServiceImpl implements CampaignChHundredService {

    private final RestTemplate restTemplate;

    public CampaignChHundredServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Object getCampaign(int offset, int limit) {
        String url = getCampaignServiceUrl() + "/ch-100/get";
        return restTemplate.exchange(url, null, null, Object.class);
    }

    private String getCampaignServiceUrl() {
        return "http://cms";
    }
}
