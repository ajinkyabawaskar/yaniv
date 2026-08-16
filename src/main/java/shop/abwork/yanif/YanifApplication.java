package shop.abwork.yanif;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "shop.abwork.yanif.repository")
@EnableRedisRepositories(value = "false")
public class YanifApplication {

	public static void main(String[] args) {
		SpringApplication.run(YanifApplication.class, args);
	}

}
