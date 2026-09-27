package com.chris64233.numberporting.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.numberporting.service.PortingService;
import com.chris64233.numberporting.service.view.EventView;

@RestController
@RequestMapping("/api/numbers/{number}/events")
public class NumberTimelineController {

    private final PortingService portingService;

    public NumberTimelineController(PortingService portingService) {
        this.portingService = portingService;
    }

    /** 号码维度切换事件时间线（不可变、按发生时间排列）。 */
    @GetMapping
    public List<EventView> timeline(@PathVariable String number) {
        return portingService.timeline(number);
    }
}
