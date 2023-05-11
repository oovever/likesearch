package org.likeserch;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 模糊查询工具类
 *
 * @author rocmu
 * @date 2023/04/24
 */
public class LikeSearch<T> {

    private final ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();
    private final Lock rLock = reentrantReadWriteLock.readLock();
    private final Lock wLock = reentrantReadWriteLock.writeLock();

    private final Node<T>[] nodes = new Node[Character.MAX_VALUE];
    private final Map<T, Set<String>> targetWordsMap = new HashMap<>();

    public void put(T targetWords, String searchWords) {
        wLock.lock();
        try {
            if (targetWordsMap.containsKey(targetWords) && targetWordsMap.get(targetWords).contains(searchWords)) {
                return;
            }
            char[] charArray = searchWords.toCharArray();
            for (int i = 0; i < charArray.length; i++) {
                char c = charArray[i];
                Node<T> node = nodes[c];
                if (node == null) {
                    node = new Node<T>();
                    nodes[c] = node;
                }
                node.add(targetWords, (short) i);
            }
            if (!targetWordsMap.containsKey(targetWords)) {
                targetWordsMap.put(targetWords, new HashSet<>());
            }
            targetWordsMap.get(targetWords).add(searchWords);
        } finally {
            wLock.unlock();
        }

    }

    public boolean remove(T targetWords) {
        wLock.lock();
        try {
            if (!targetWordsMap.containsKey(targetWords)) {
                return false;
            }
            boolean success = false;
            for (String searchWord : targetWordsMap.get(targetWords)) {
                char[] searchWordArr = searchWord.toCharArray();
                for (char wordChar : searchWordArr) {
                    if (nodes[wordChar] != null) {
                        success = nodes[wordChar].remove(targetWords);
                    }
                }
            }
            targetWordsMap.remove(targetWords);
            return success;
        } finally {
            wLock.unlock();
        }

    }

    public void update(T removeWords, T newTargetWords, String newSearchWords) {
        wLock.lock();
        try {
            boolean removeResult = remove(removeWords);
            targetWordsMap.remove(removeWords);
            if (removeResult) {
                put(newTargetWords, newSearchWords);
            }
        } finally {
            wLock.unlock();
        }
    }

    public Collection<T> search(String searchWords, int limit, LikeSearchType searchType) {
        rLock.lock();
        try {
            if (StringUtils.isBlank(searchWords)) {
                return new ArrayList<>(targetWordsMap.keySet()).subList(0, limit);
            }
            char[] charArray = searchWords.toCharArray();
            int matchSize = 0;
            ResultContext context = new ResultContext();
            for (char c : charArray) {
                Node<T> node = nodes[c];
                if (node == null) {
                    break;
                }
                if (!context.match(node, searchType, searchWords)) {
                    break;
                }
                matchSize++;
            }
            if (matchSize == searchWords.length()) {
                return context.limit(limit);
            }
            return Collections.emptyList();
        } finally {
            rLock.unlock();
        }

    }

    public enum LikeSearchType {

        EXACT_MATCH(0, "精确匹配"),
        LIKE_MATCH(1, "模糊匹配"), // 匹配字符必须连续，比如 "天气好"与"天气"、"气好"匹配，但是与"天好不配"
        SUPER_LIKE_MATCH(2, "模糊匹配"); // "匹配字符可以不连续,比如 "天气好"可以和"天好匹配""


        private final int value;
        private final String desc;

        LikeSearchType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }
    }

    private static class Node<T> {

        Map<T, CachedHashSet<Short>> indexMap = new HashMap<>();

        private void add(T targetWords, Short index) {
            Set<Short> indexSet = indexMap.computeIfAbsent(targetWords, k -> new CachedHashSet<>());
            indexSet.add(index);
        }

        private boolean remove(T targetWords) {
            if (indexMap.containsKey(targetWords)) {
                indexMap.remove(targetWords);
                return true;
            }
            return false;
        }

    }

    private static class CachedHashSet<E> extends LinkedHashSet<E> {

        private E last = null;

        @Override
        public boolean add(E e) {
            last = e;
            return super.add(e);
        }

        public E getLast() {
            return last;
        }

    }

    private class ResultContext {

        Map<T, Set<Short>> result;
        boolean isFirstIndex = false;

        private boolean match(Node<T> node, LikeSearchType searchType, String searchWords) {
            if (!isFirstIndex) {
                result = new HashMap<>(node.indexMap);
                isFirstIndex = true;
                return true;
            }
            boolean isMatch = false;
            Map<T, Set<Short>> newResult = new HashMap<>();
            for (Entry<T, CachedHashSet<Short>> entry : node.indexMap.entrySet()) {
                if (!result.containsKey(entry.getKey())) { // 之前的targetValue不包含当前的
                    continue;
                }
                Set<Short> beforeIndexSet = result.get(entry.getKey());
                boolean currentMatch = false;
                for (Short beforeIndex : beforeIndexSet) {
                    if (containIndex(entry, ++beforeIndex, searchType, searchWords)) {
                        currentMatch = true;
                        break;
                    }
                }
                if (currentMatch) {
                    newResult.put(entry.getKey(), entry.getValue());
                    isMatch = true;
                }
            }
            result = newResult;
            return isMatch;
        }

        private Collection<T> limit(int limit) {
            if (limit <= 0) {
                return Collections.emptyList();
            }
            if (result.size() <= limit) {
                return result.keySet().stream().sorted().collect(Collectors.toList());
            }
            return result.keySet().stream().sorted().collect(Collectors.toList()).subList(0, limit);
        }

        public boolean containIndex(Entry<T, CachedHashSet<Short>> currentIndexEntry, Short expectIndex,
                                    LikeSearchType searchType, String searchWords) {
            switch (searchType) {
                case EXACT_MATCH:
                    return currentIndexEntry.getValue().contains(expectIndex) && currentIndexEntry.getKey()
                            .equals(searchWords);
                case LIKE_MATCH:
                    return currentIndexEntry.getValue().contains(expectIndex);
                default:
                    return currentIndexEntry.getValue().getLast() >= expectIndex;
            }
        }
    }
}

