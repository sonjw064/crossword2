package crossword2.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정기 정리 작업(refresh 토큰, 이후 게스트 문의 익명화 등)을 위해 스케줄링을 켠다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
