package com.hdh.lifeup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @author  hdonghong
 * @since 2018/07/15
 */
@Slf4j
@Controller
@EnableCaching
@EnableScheduling
@SpringBootApplication
public class LifeupApplication {

	public static void main(String[] args) {
		SpringApplication.run(LifeupApplication.class, args);
	}

	@RequestMapping(value = {"/", "index", "index.html"})
	public String home() {
		return "redirect:http://lifeup.hdonghong.top/index.html";
	}

}
