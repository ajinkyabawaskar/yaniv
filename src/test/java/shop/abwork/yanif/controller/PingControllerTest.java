package shop.abwork.yanif.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import shop.abwork.yanif.controller.PingController;

class PingControllerTest {

    @Test
    void pingReturnsPongWhenRedisIsAvailable() throws Exception {
        StringRedisTemplate stringRedisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(stringRedisTemplate.execute((RedisCallback<String>) any())).thenReturn("PONG");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PingController(stringRedisTemplate)).build();

        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }
}
