package com.tataplay.admanagement.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campaign-ch-100")
public class CampaignChHundredController {

    private final CampaignChHundredService campaignChHundredService;

    public CampaignChHundredController(CampaignChHundredService campaignChHundredService) {
        this.campaignChHundredService = campaignChHundredService;
    }

    @GetMapping
    public Object getCampaign() {
        return campaignChHundredService.getCampaign(0, 100);
    }
}
