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

    @org.springframework.beans.factory.annotation.Value("${stellar.grooves.demoMode:false}")
    private boolean demoMode;

    @org.springframework.beans.factory.annotation.Value("${stellar.grooves.demo.username:demo}")
    private String demoUsername;

    @org.springframework.beans.factory.annotation.Value("${stellar.grooves.demo.password:}")
    private String demoPassword;

    @GetMapping("/")
    public String index(@CurrentUser User user, Model model) {
        model.addAttribute("userId", user.getId());
        model.addAttribute("appVersion", appVersion);
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("demoMode", demoMode);
        if (demoMode) {
            model.addAttribute("demoUsername", demoUsername);
            model.addAttribute("demoPassword", demoPassword);
        }
        return "login";
    }

    @GetMapping("/signup")
    public String signup() {
        // Self-signup is disabled on public demo instances — send visitors to
        // the (pre-filled) demo login instead of a dead-end empty account.
        if (demoMode) {
            return "redirect:/login";
        }
        return "signup";
    }

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("appVersion", appVersion);
        return "help";
    }

    @GetMapping("/shared/smart-playlists/{token}")
    public String sharedSmartPlaylist(@org.springframework.web.bind.annotation.PathVariable String token,
                                      Model model) {
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("shareToken", token);
        return "shared-smart-playlist";
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin(Model model) {
        model.addAttribute("appVersion", appVersion);
        return "admin";
    }
}
