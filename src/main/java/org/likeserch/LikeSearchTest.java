package org.likeserch;


import java.util.ArrayList;
import java.util.Collection;
import org.likeserch.LikeSearch.LikeSearchType;

public class LikeSearchTest {

    static LikeSearch<String> likeSearch = new LikeSearch<>();

    public static void main(String[] args) {
        testAdd();
        testSuccessExactSearch();
        testFailedExactSearch();
        testLikeSearch();
        testSuperLikeSearch();
        testRemove();
        testUpdate();
    }

    private static void testAdd() {
        likeSearch.put("今天天气真好", "今天天气真好");
        likeSearch.put("今天天气真好", "天气怎么样");
        likeSearch.put("天天", "天天");
        likeSearch.put("天气", "天气");
        likeSearch.put("真好", "真好");
        likeSearch.put("明天天气不好", "明天天气不好");
    }

    private static void testSuccessExactSearch() {
        Collection<String> result = likeSearch.search("天气", 1, LikeSearchType.EXACT_MATCH);
        assert !result.isEmpty();
        assert new ArrayList<>(result).get(0).equals("天气");
    }

    private static void testFailedExactSearch() {
        Collection<String> result = likeSearch.search("天气", 1, LikeSearchType.EXACT_MATCH);
        assert !result.isEmpty();
    }

    private static void testLikeSearch() {
        Collection<String> result = likeSearch.search("天天", 10, LikeSearchType.LIKE_MATCH);
        System.out.println(result);
        assert !result.isEmpty();
        assert new ArrayList<>(result).contains("天天");
        assert new ArrayList<>(result).contains("明天天气不好");
        assert new ArrayList<>(result).contains("今天天气真好");
    }

    private static void testSuperLikeSearch() {
        Collection<String> result = likeSearch.search("天好", 10, LikeSearchType.SUPER_LIKE_MATCH);
        System.out.println(result);
        assert !result.isEmpty();
        assert new ArrayList<>(result).contains("明天天气不好");
        assert new ArrayList<>(result).contains("今天天气真好");
    }

    private static void testRemove() {
        likeSearch.remove("天天");
        Collection<String> result = likeSearch.search("天天", 1, LikeSearchType.EXACT_MATCH);
        assert result.isEmpty();
        likeSearch.put("天天", "天天");
        result = likeSearch.search("天天", 1, LikeSearchType.EXACT_MATCH);
        assert new ArrayList<>(result).get(0).equals("天天");
    }

    private static void testUpdate() {
        Collection<String> result = likeSearch.search("真好", 1, LikeSearchType.EXACT_MATCH);
        assert new ArrayList<>(result).get(0).equals("真好");
        likeSearch.update("真好", "真的好", "真的好");
        result = likeSearch.search("真好", 1, LikeSearchType.EXACT_MATCH);
        assert result.isEmpty();
        result = likeSearch.search("真的好", 1, LikeSearchType.EXACT_MATCH);
        assert new ArrayList<>(result).get(0).equals("真的好");
    }
}
