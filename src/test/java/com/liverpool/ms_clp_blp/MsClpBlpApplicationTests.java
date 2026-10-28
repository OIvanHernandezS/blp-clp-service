package com.liverpool.ms_clp_blp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

@ServiceCredential
@SpringBootTest(properties = "management.server.port=0")
class MsClpBlpApplicationTests {

	@Test
	void contextLoads() {
	}

}
