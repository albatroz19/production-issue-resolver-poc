package com.tataplay.cms.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campaign-management/ch-100")
public class ChHundredCampaignController {

    private final ChHundredCampaignService chHundredCampaignService;

    public ChHundredCampaignController(ChHundredCampaignService chHundredCampaignService) {
        this.chHundredCampaignService = chHundredCampaignService;
    }

    @PostMapping("/get")
    public Object getCampaign() {
        return chHundredCampaignService.getCampaign(0, 100);
    }
}
