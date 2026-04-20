package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.MusicFileDTO;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.service.RediscoveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/library/rediscovery")
@Tag(name = "Rediscovery", description = "Curator surfaces over play history, ratings, and catalog coverage")
public class RediscoveryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RediscoveryService service;

    public RediscoveryController(RediscoveryService service) {
        this.service = service;
    }

    @GetMapping("/forgotten")
    public ResponseEntity<?> forgotten(@CurrentUser User user,
                                       @RequestParam(required = false) Integer days,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        Pageable pageable = pageable(page, size);
        Page<MusicFileDTO> result = service.findForgotten(user.getId(), days, pageable);
        return ResponseEntity.ok(body(result));
    }

    @GetMapping("/neglected-favorites")
    public ResponseEntity<?> neglectedFavorites(@CurrentUser User user,
                                                @RequestParam(required = false) Integer minRating,
                                                @RequestParam(required = false) Integer days,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        Pageable pageable = pageable(page, size);
        Page<MusicFileDTO> result = service.findNeglectedFavorites(user.getId(), minRating, days, pageable);
        return ResponseEntity.ok(body(result));
    }

    @GetMapping("/one-hit-wonders")
    public ResponseEntity<?> oneHitWonders(@CurrentUser User user,
                                           @RequestParam(required = false) Integer minCatalog,
                                           @RequestParam(required = false) Integer limit) {
        List<RediscoveryService.OneHitWonder> items = service.findOneHitWonders(user.getId(), minCatalog, limit);
        return ResponseEntity.ok(Map.of("items", items));
    }

    private static Pageable pageable(Integer page, Integer size) {
        int p = page != null && page >= 0 ? page : 0;
        int s = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 20;
        return PageRequest.of(p, s);
    }

    private static Map<String, Object> body(Page<MusicFileDTO> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("total", result.getTotalElements());
        return body;
    }
}
