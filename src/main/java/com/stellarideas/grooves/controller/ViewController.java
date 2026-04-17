package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.model.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @org.springframework.beans.factory.annotation.Value("${app.version:dev}")
    private String appVersion;

    @GetMapping("/")
    public String index(@CurrentUser User user, Model model) {
        model.addAttribute("userId", user.getId());
        model.addAttribute("appVersion", appVersion);
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/signup")
    public String signup() {
        return "signup";
    }

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("appVersion", appVersion);
        return "help";
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin(Model model) {
        model.addAttribute("appVersion", appVersion);
        return "admin";
    }
}
