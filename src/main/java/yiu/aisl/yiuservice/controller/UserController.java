package yiu.aisl.yiuservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import yiu.aisl.yiuservice.repository.UserRepository;
import yiu.aisl.yiuservice.service.MainService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {
    private final UserRepository userRepository;
    private final MainService mainService;

}
