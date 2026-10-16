package cl.barriodigital.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "barriodigital.notify.listeners-auto-startup=false",
        "barriodigital.notify.storage-directory=target/context-test-data"
})
class NotifyApplicationTests {

	@Test
	void contextLoads() {
	}

}
