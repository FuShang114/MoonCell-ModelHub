package com.mooncell.gateway.core.dao;

import com.mooncell.gateway.core.model.ModelInstance;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ModelInstanceMapper {

    @Select("""
        SELECT m.*, p.name as provider_name
        FROM model_instance m 
        LEFT JOIN provider p ON m.provider_id = p.id
        WHERE m.model_name = #{modelName}
    """)
    List<ModelInstance> findByModelName(String modelName);

    @Select("""
        SELECT m.*, p.name as provider_name
        FROM model_instance m 
        LEFT JOIN provider p ON m.provider_id = p.id
    """)
    List<ModelInstance> findAll();

    @Select("SELECT * FROM model_instance WHERE url = #{url}")
    ModelInstance findByUrl(String url);

    @Insert("""
        INSERT INTO model_instance (provider_id, model_name, url, api_key, post_model,
                                    response_request_id_path, response_content_path, response_seq_path,
                                    response_raw_enabled, weight, max_qps, is_active)
        VALUES (#{providerId}, #{modelName}, #{url}, #{apiKey}, #{postModel},
                #{responseRequestIdPath}, #{responseContentPath}, #{responseSeqPath},
                #{responseRawEnabled}, #{weight}, #{maxQps}, #{isActive})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ModelInstance instance);

    @Update("UPDATE model_instance SET is_active = #{isActive} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("isActive") Boolean isActive);
    
    @Update("UPDATE model_instance SET weight = #{weight} WHERE id = #{id}")
    int updateWeight(@Param("id") Long id, @Param("weight") Integer weight);

    @Update("UPDATE model_instance SET post_model = #{postModel} WHERE id = #{id}")
    int updatePostModel(@Param("id") Long id, @Param("postModel") String postModel);

    @Update("""
        UPDATE model_instance
        SET provider_id = #{providerId},
            model_name = #{modelName},
            url = #{url},
            api_key = #{apiKey},
            post_model = #{postModel},
            response_request_id_path = #{responseRequestIdPath},
            response_content_path = #{responseContentPath},
            response_seq_path = #{responseSeqPath},
            response_raw_enabled = #{responseRawEnabled},
            max_qps = #{maxQps},
            is_active = #{isActive}
        WHERE id = #{id}
    """)
    int updateInstance(@Param("id") Long id,
                       @Param("providerId") Long providerId,
                       @Param("modelName") String modelName,
                       @Param("url") String url,
                       @Param("apiKey") String apiKey,
                       @Param("postModel") String postModel,
                       @Param("responseRequestIdPath") String responseRequestIdPath,
                       @Param("responseContentPath") String responseContentPath,
                       @Param("responseSeqPath") String responseSeqPath,
                       @Param("responseRawEnabled") Boolean responseRawEnabled,
                       @Param("maxQps") Integer maxQps,
                       @Param("isActive") Boolean isActive);

    @Select("SELECT id FROM provider WHERE name = #{name}")
    Long findProviderIdByName(String name);
    
    @Insert("INSERT INTO provider (name, description) VALUES (#{name}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProvider(@Param("name") String name, @Param("description") String description);

    @Select("SELECT id, name, description FROM provider ORDER BY id DESC")
    List<com.mooncell.gateway.dto.ProviderDto> findAllProviders();

    @Update("UPDATE provider SET name = #{name}, description = #{description} WHERE id = #{id}")
    int updateProvider(@Param("id") Long id, @Param("name") String name, @Param("description") String description);
}


