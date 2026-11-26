package com.example.kb.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 问题向量缓存：同一问题短时间重复提问只向量化一次，空格差异归一后也命中。 */
@ExtendWith(MockitoExtension.class)
class QueryEmbeddingCacheTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    void sameQuestionEmbedsOnce() {
        Embedding vector = Embedding.from(new float[] {0.1f, 0.2f});
        when(embeddingModel.embed("问题")).thenReturn(Response.from(vector));
        QueryEmbeddingCache cache = new QueryEmbeddingCache(embeddingModel);

        Embedding first = cache.embed("问题");
        Embedding second = cache.embed(" 问题 ");

        assertEquals(vector, first);
        assertEquals(vector, second);
        verify(embeddingModel, times(1)).embed("问题");
    }

    @Test
    void differentQuestionsEmbedSeparately() {
        when(embeddingModel.embed("甲")).thenReturn(Response.from(Embedding.from(new float[] {1f})));
        when(embeddingModel.embed("乙")).thenReturn(Response.from(Embedding.from(new float[] {2f})));
        QueryEmbeddingCache cache = new QueryEmbeddingCache(embeddingModel);

        cache.embed("甲");
        cache.embed("乙");

        verify(embeddingModel).embed("甲");
        verify(embeddingModel).embed("乙");
    }
}
