package com.wardrobe.platform.service.impl;

import com.wardrobe.common.constant.IDBConstant;
import com.wardrobe.common.po.SysResources;
import com.wardrobe.common.util.DateUtil;
import com.wardrobe.common.util.StrUtil;
import com.wardrobe.platform.service.IResourceService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by Administrator on 2018/1/12.
 */
@Service
public class ResourceServiceImpl extends BaseService implements IResourceService {

    @Override
    public List<SysResources> getKeyResources(List<SysResources> resources){
        List<SysResources> rs = new ArrayList<>();
        if(resources != null && resources.size() > 0){
            for(SysResources r : resources){
                SysResources nr = new SysResources();
                nr.setResourcePath(r.getResourcePath());
                nr.setResourceName(r.getResourceName());
                nr.setResourceType(r.getResourceType());
                rs.add(nr);
            }
        }
        return rs;
    }

    @Override
    public void save(SysResources resource) {
        baseDao.save(resource, resource.getResourceId());
    }

    @Override
    public void delete(int resourceId) {
        baseDao.updateBySql("DELETE FROM resource WHERE resourceId=?", resourceId);
    }

    @Override
    public SysResources deleteAndReturn(int resourceId) {
        SysResources resource = getResourceById(resourceId);
        delete(resourceId);
        return resource;
    }

    @Override
    public void delete(int resourceServiceId, String resourceServiceType){
        baseDao.updateBySql("DELETE FROM resource WHERE resourceServiceId=? AND resourceServiceType=?", resourceServiceId, resourceServiceType);
    }

    @Override
    public SysResources getResourceById(int resourceId){
        return baseDao.getToEvict(SysResources.class, resourceId);
    }

    @Override
    public List<Integer> saveResources(List<SysResources> resources){
        List<Integer> resourceIds = new ArrayList();
        for(SysResources r : resources){
            baseDao.save(r, null);
            resourceIds.add(r.getResourceId());
        }
        return resourceIds;
    }

    @Override
    public void updateResources(String[] resourceIds, int resourceServiceId, String resourceServiceType, String resourceType){
        for(String srId : resourceIds){
            if(StrUtil.isNotBlank(srId)){
                SysResources resource = getResourceById(StrUtil.objToInt(srId));
                if(StrUtil.isBlank(resource.getResourceServiceType())&&StrUtil.isBlank(resource.getResourceType())&&resource.getResourceServiceId() == IDBConstant.ZERO){
                    resource.setResourceServiceId(resourceServiceId);
                    resource.setResourceServiceType(resourceServiceType);
                    resource.setResourceType(resourceType);
                    baseDao.save(resource, resource.getResourceId());
                }
            }
        }
    }

    @Override
    public SysResources getResource(int resourceServiceId, String resourceServiceType){
        return baseDao.queryByHqlFirst("FROM Resource WHERE resourceServiceId=? AND resourceServiceType=?", resourceServiceId, resourceServiceType);
    }

    @Override
    public SysResources getResource(int resourceServiceId, String resourceServiceType, int resourceSeq){
        return baseDao.queryByHqlFirst("FROM Resource WHERE resourceServiceId=? AND resourceServiceType=? AND resourceSeq=?", resourceServiceId, resourceServiceType, resourceSeq);
    }

    @Override
    public SysResources saveResource(MultipartFile multipartFile, int serviceId, String serviceType, String resourceType, String fold){
        String originalFilename = multipartFile.getOriginalFilename();
        String resourceName = StrUtil.getUUID() + originalFilename.substring(originalFilename.lastIndexOf("."));
        SysResources resource = new SysResources();
        resource.setCreateTime(DateUtil.getNowDate());
        resource.setFileSize(multipartFile.getSize());
        resource.setResourceServiceId(serviceId);
        resource.setResourceServiceType(serviceType);
        resource.setResourceOriginal(originalFilename);
        resource.setResourceName(resourceName);
        resource.setStatus(IDBConstant.LOGIC_STATUS_YES);
        resource.setResourceType(resourceType);
        resource.setResourcePath(fold + "/" + resourceName);
        baseDao.save(resource, resource.getResourceId());
        return resource;
    }

}