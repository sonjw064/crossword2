package crossword2.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 1단계 임시 설정: 인증은 2단계(JWT)에서 구현한다. */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico", "/css/**", "/js/**").permitAll()
				.anyRequest().denyAll())
			.httpBasic(Customizer.withDefaults());
		return http.build();
	}
}
