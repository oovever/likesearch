# 基于内存的模糊查询方案
## 背景
![搜索示例](https://github.com/oovever/asserts/blob/master/like_search/fuzz_search.png?raw=true)
&emsp;业务开发过程中，经常会遇到**搜索提示**相关需求，针对此类需求我们有多种实现方式：
- 基于mysql的like查询
    - 优点：
        - 使用mysql原生的语法，可以支持查询，无需引入额外的中间件
        - 模糊查询字段加索引，可以提升部分查询性能
    - 缺点：
        - like %% 模糊查询，无法充分利用索引特性，极端情况下，需要遍历整个索引树
        - - 模糊查询字段必须是单值
            - 如针对一个游戏可能有多个游戏公司的情况，数据库使用json方式存储多值（["京东"，"广西京东"]），无法使用like方式进行查询
            - 针对此类情况，需要新建一张数据表，单独存储需要模糊查询的字段，然后使用like进行查询
        - 模糊查询词必须连续
            - 如：目标词：**今天天气真好啊**，查询like%天气%、like%今天%都可以获得到预期结果，但是查询**like%今好%** 缺无法得到匹配结果
        - 查询字段建立索引有一定空间开销
            - 如果存储的目标字段长度较大，会占用较多的存储空间
        - 有网络开销：通过数据库的查询方式，有网络延迟
        - 占用连接池资源：简单的查询占用数据库连接池资源
    - 场景
        - 简单的模糊查询可以使用
- 基于es查询
    - 优点
        - 快速搜索：使用倒排索引技术，可以快速搜索大量数据
        - 强大的查询语言：Elasticsearch提供了丰富的查询语言，可以进行全文搜索、聚合、过滤等操作
    - 缺点
        - 引入新的组件，有一定学习、维护成本
        - 内存占用较高：Elasticsearch需要占用较多的内存来运行
        - 有网络开销：需要调用es集群的api，获取查询结果，有一定的网络开销
    - 场景
        - 大数据量查询、模糊查询可以使用

实际业务场景中部分需求的数据量并不大，但是要求高效的模糊查询方式，小数据量引入ES，有些”大材小用“，不但增加了维护成本、同时也有一定的网络开销；又由于mysql like固有的缺陷，可能无法满足高效多场景的需求，同样的请求mysql也有一定网络开销；那么针对小数据量模糊查询的场景，有没有一种方式可以既高效，又能按业务不同查询诉求准确出查询所需的数据呢？这就是我们要介绍的**基于内存构建的模糊查询**方式

## 基于内存的模糊查询

&emsp;首先我们要了解，没有万能的方案，只有当前场景最适合的方案；正所谓**背着抱着一样沉**,我们要规避使用mysql、es查询所带来的问题，就要使用一些其他资源作为交换。**空间换时间** 是一种常见的优化策略，它的基本思想是通过增加空间复杂度来降低时间复杂度。这种策略通常用于需要频繁访问某些数据或计算结果的场景，通过事先计算并缓存这些数据或结果，可以避免重复计算，从而提高程序的执行效率。在计算机科学中，空间换时间被广泛应用于算法设计、程序优化等方面。我们的方案基于**空间换时间** 思路进行设计，下面将详细描述。

&emsp;ES使用倒排索引技术使得搜索引擎能够快速地查找包含特定词汇的文档，并按相关性排序;基于倒排索引的思路，我们设计了一个Character.MAX_VALUE大小的**Node数组**，用于存储每一个字符索引，每一个字符使用**indexMap**表示其对应的原始词，和当前字符的索引，结构如下图所示
![结构示例](https://github.com/oovever/asserts/blob/master/like_search/likeSearch_add.png?raw=true)
查询算法描述
1. 遍历所有查询关键词的字符
2. 如果当前字符是第一个字符，获取当前字符对应的**indexMap**记为**baseIndexMap**，查询结果只能从**baseIndexMap**的keySet获取，从**baseIndexMap**的valueSet记为**baseKeySet**
3. 记录当前匹配的索引**beforeIndexSet**
4. 遍历关键词的后续字符，并获取字符对应的**indexMap**，记为**currentIndexMap**
5. remove key不在**baseKeySet**的值
6. 如果删除后**currentIndexMap**为空，直接返回，表示当前查询关键词没有匹配的记录
7. 遍历**beforeIndexSet**
8. 否则根据匹配方式，进行校验
    1. 精确匹配
        1. **currentIndexMap**中的indexValueSet是否包含beforeIndex+1,表示索引是否连续
        2. 如果包含，判断查询关键词是否与目标词相等
        3. 条件符合，返回匹配，这个关键词可以加到候选关键词集合中
    2. 模糊匹配
        1. **currentIndexMap**中的indexValueSet是否包含beforeIndex+1,表示索引是否连续
        2. 条件符合，返回匹配，这个关键词可以加到候选关键词集合中
    3. 超级模糊匹配
        1. **currentIndexMap**中的indexValueSet是否包含大于等于beforeIndex+1的索引，表示可以不连续查询
9. 根据限制返回条目，对候选返回集合进行排序过滤后，返回
   ![结构示例](https://github.com/oovever/asserts/blob/master/like_search/likesearch_search.png?raw=true)

## 快速开始
根据要查询的目标词类型，新建LikeSearch对象
``` java
LikeSearch<String> likeSearch = new LikeSearch<>()
```
- 添加
``` java
 private static void testAdd() {
    likeSearch.put("今天天气真好", "今天天气真好");
    likeSearch.put("今天天气真好", "天气怎么样");
    likeSearch.put("天天", "天天");
    likeSearch.put("天气", "天气");
    likeSearch.put("真好", "真好");
    likeSearch.put("明天天气不好", "明天天气不好");
  }
```
kv方式添加，key是要查询的结果值，value是需要进行模糊查询的值；这样的设计方式，方便后续扩展，针对一个结果词，如果有多个搜索词可以查询，那么可以添加多次；如示例中的**今天天气真好**结果，可以分别通过**天气怎么样**，**今天天气真好**查询获取；日常我们开发过程中，如果只针对某个单一的词做检索，那么将kev和value设置成相同的值即可
- 查询
- 查询参数
    - 模糊查询词
    - 返回条数
    - 匹配方式
      查询支持三种方式，分别是1）EXACT_MATCH:精确匹配,2) LIKE_MATCH:模糊匹配;3)SUPER_LIKE_MATCH:超级模糊匹配
1) 精确匹配
``` java
 private static void testSuccessExactSearch() {
    Collection<String> result = likeSearch.search("天气", 1, LikeSearchType.EXACT_MATCH);
    assert !result.isEmpty();
    assert new ArrayList<>(result).get(0).equals("天气");
  }
   private static void testFailedExactSearch() {
    Collection<String> result = likeSearch.search("天气", 1, LikeSearchType.EXACT_MATCH);
    assert !result.isEmpty();
  }
```

精确匹配的查询词，必须和添加时的查询词（第一步添加过程中的value）严格一致，我们可以在添加时将value与key设置成同样的时，使用精确匹配模式完成精确查询
2) 模糊匹配
``` java
private static void testLikeSearch() {
    Collection<String> result = likeSearch.search("天天", 10, LikeSearchType.LIKE_MATCH);
    System.out.println(result);
    assert !result.isEmpty();
    assert new ArrayList<>(result).contains("天天");
    assert new ArrayList<>(result).contains("明天天气不好");
    assert new ArrayList<>(result).contains("今天天气真好");
  }
```
模糊匹配与传统使用like%searchWord%匹配方式相似，要求被匹配的关键词必须连续
3)超级模糊匹配
``` java
 private static void testSuperLikeSearch() {
    Collection<String> result = likeSearch.search("天好", 10, LikeSearchType.SUPER_LIKE_MATCH);
    System.out.println(result);
    assert !result.isEmpty();
    assert new ArrayList<>(result).contains("明天天气不好");
    assert new ArrayList<>(result).contains("今天天气真好");
  }
```
在我们日常开发中，总会有部分需求，总会期望匹配的数据足够多、足够全，在不适用es的情况下，无论是使用mysql的like还是在内存中使用contains都无法满足我们的需求，基于此我们也提供了一种**超级模糊匹配**，查询词可以不连续，只要目标词包含所有查询字符，并且顺序与查询字符一致，我们就作为候选项返回
- 删除
```java
  private static void testRemove() {
    likeSearch.remove("天天");
    Collection<String> result = likeSearch.search("天天", 1, LikeSearchType.EXACT_MATCH);
    assert result.isEmpty();
    likeSearch.put("天天", "天天");
    result = likeSearch.search("天天", 1, LikeSearchType.EXACT_MATCH);
    assert new ArrayList<>(result).get(0).equals("天天");
  }
```
- 更新
```java
 private static void testUpdate() {
    Collection<String> result = likeSearch.search("真好", 1, LikeSearchType.EXACT_MATCH);
    assert new ArrayList<>(result).get(0).equals("真好");
    likeSearch.update("真好", "真的好", "真的好");
    result = likeSearch.search("真好", 1, LikeSearchType.EXACT_MATCH);
    assert result.isEmpty();
    result = likeSearch.search("真的好", 1, LikeSearchType.EXACT_MATCH);
    assert new ArrayList<>(result).get(0).equals("真的好");
  }
```
更新即将原目标词删除，插入新目标词
## TOOD 性能测试
## 问题
- 为什么Node要用数组而不用hashMap
    - 少一次哈希计算，基于字符char可以直接定位到数组中的位置
    - hashMap拉链式存储，hash冲突严重时影响查询性能
    - hashMap的优点
        - 数组方式，当前使用分字符存储；针对**词**的存储也是拆分成字符后存储；无法将**词**本身作为一个整体存储，这方面使用hashMap有其优势
- 为什么自建CachedHashSet
    - 针对非连续词的模糊查询(**SUPER_LIKE_MATCH**)，需要判断indexMap中是否包含大于等于预期字符的索引；由于索引插入时是按序插入，所以只需要判断最后一个索引是否大于预期索引字段即可。使用CachedHashSet在插入时维护最后位置的索引值，使用O（1）复杂度获取最大索引。
    - 针对其他场景的查询需求，使用set结构可以使用O（1）复杂度快速判断预期索引是否存在
## 下一步
- 基于分词器，构建插入请求，使得查询、存储可以更高效
## 缺陷
- 启动时需要加载所有要模糊查询的词到内存，影响启动性能
- 数据存放到内存中，占用内存空间