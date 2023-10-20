package io.github.majianzheng.jarboot.dao;

import io.github.majianzheng.jarboot.entity.FileUploadProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @author majianzheng
 */
@Repository
public interface FileUploadProgressDao extends JpaRepository<FileUploadProgress, Long> {
    /**
     * 根据dstPath获取上传进度
     * @param dstPath 目的路径
     * @return 进度
     */
    FileUploadProgress getFileUploadProgressByDstPath(String dstPath);
}