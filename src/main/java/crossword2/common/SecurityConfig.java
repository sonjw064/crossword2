package crossword2.common;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfig {

	private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
			+ "font-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; "
			+ "form-action 'self'";

	/**
	 * Bearer 토큰(JWT) 기반 무상태 인증. 쿠키 세션을 쓰지 않으므로 CSRF 보호는 끄고, 다른 출처(CORS)는 허용하지 않는다.
	 * 토큰이 없어도 풀 수 있는 익명 풀이를 위해 /api/auth, 퍼즐/단어 API는 공개이고 /api/me는 로그인(게스트 포함)이 필요하다.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.headers(h -> h
				.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
				.referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/**", "/api/health").permitAll()
				.requestMatchers("/api/me", "/api/me/**").authenticated()
				.requestMatchers("/api/feedback", "/api/feedback/**").authenticated()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.requestMatchers("/api/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico", "/css/**", "/js/**").permitAll()
				.anyRequest().denyAll())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
				.authenticationEntryPoint((req, res, e) -> writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
						"authentication is required or the token is invalid"))
				.accessDeniedHandler((req, res, e) -> writeError(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
						"access denied")))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((req, res, e) -> writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
						"authentication is required"))
				.accessDeniedHandler((req, res, e) -> writeError(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
						"access denied")));
		return http.build();
	}

	private static void writeError(HttpServletResponse res, HttpStatus status, String code, String message)
			throws IOException {
		res.setStatus(status.value());
		res.setContentType("application/json;charset=UTF-8");
		res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}
}
