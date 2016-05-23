package am.ik.home;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@SpringBootApplication
//@EnableResourceServer
@RestController
public class PredictApplication {

    public static void main(String[] args) {
        SpringApplication.run(PredictApplication.class, args);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;
    RestTemplate restTemplate = new RestTemplate();
    @Value("${mecab-api.url}")
    String mecabApiUrl;

    List<String> extractWords(String outcomeName) throws Exception {
        JsonNode node = restTemplate.getForObject(UriComponentsBuilder.fromUriString(mecabApiUrl)
                .queryParam("text", UriUtils.encode(outcomeName.trim(), "UTF-8"))
                .build(true).toUri(), JsonNode.class);
        List<String> words = StreamSupport.stream(node.get("nodes").spliterator(), false)
                .filter(n -> "名詞".equals(n.get("feature").get(0).asText()) && "一般".equals(n.get("feature").get(1).asText()))
                .map(n -> n.get("surface").asText())
                .filter(s -> s.length() > 1)
                .distinct()
                .collect(Collectors.toList());
        return words.isEmpty() ? Collections.singletonList(outcomeName) : words;
    }

    @RequestMapping(value = "api/train", method = RequestMethod.POST)
    Object train(@RequestParam String outcomeName, @RequestParam Integer categoryId) throws Exception {
        List<String> target = extractWords(outcomeName);
        int point = target.size() == 1 ? 10 : 1;
        jdbcTemplate.batchUpdate("INSERT INTO predict(word, category_id, cnt) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE word = ?, cnt  = cnt + ?", target.stream()
                .map(s -> new Object[]{s, categoryId, point, s, point}).collect(Collectors.toList()));
        return new LinkedHashMap<String, Object>() {
            {
                put("words", target);
                put("categoryId", categoryId);
            }
        };
    }

    @RequestMapping(value = "api/predict", method = {RequestMethod.POST, RequestMethod.GET})
    Object predict(@RequestParam String outcomeName) throws Exception {
        List<String> target = extractWords(outcomeName);
        Map<Integer, Integer> candidate = new HashMap<>();

        target.forEach(w -> {
            List<Map<String, Object>> ret = jdbcTemplate.queryForList("SELECT category_id, cnt FROM predict WHERE word = ?", w);
            ret.forEach(x -> {
                Integer categoryId = (Integer) x.get("category_id");
                Integer cnt = (Integer) x.get("cnt");
                candidate.computeIfPresent(categoryId, (k, v) -> v + cnt + 10);
                candidate.putIfAbsent(categoryId, cnt);
            });
        });
        long sum = candidate.values().stream().mapToInt(Integer::intValue).sum();
        return candidate.entrySet().stream()
                .map(e -> new LinkedHashMap<String, Object>() {
                    {
                        put("categoryId", e.getKey());
                        put("probability", ((double) e.getValue()) / sum);
                    }
                }).collect(Collectors.toList());
    }

    @Bean
    Filter corsFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setHeader("Access-Control-Allow-Origin", "*");
                response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, PUT, DELETE");
                response.setHeader("Access-Control-Max-Age", "3600");
                response.setHeader("Access-Control-Allow-Headers", "*");
                chain.doFilter(req, res);
            }

            @Override
            public void init(FilterConfig filterConfig) {
            }

            @Override
            public void destroy() {
            }
        };
    }
}
