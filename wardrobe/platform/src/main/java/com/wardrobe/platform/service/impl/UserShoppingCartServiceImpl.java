package com.wardrobe.platform.service.impl;

import com.wardrobe.common.bean.PageBean;
import com.wardrobe.common.constant.IDBConstant;
import com.wardrobe.common.exception.MessageException;
import com.wardrobe.common.po.*;
import com.wardrobe.common.util.Arith;
import com.wardrobe.common.util.DateUtil;
import com.wardrobe.common.util.SQLUtil;
import com.wardrobe.common.util.StrUtil;
import com.wardrobe.common.view.CommodityInputView;
import com.wardrobe.common.view.UserCouponInputView;
import com.wardrobe.platform.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by cxs on 2018/8/24.
 */
@Service
public class UserShoppingCartServiceImpl extends BaseService implements IUserShoppingCartService {

    @Autowired
    private ICommodityService commodityService;

    @Autowired
    private IUserCouponService userCouponService;

    @Autowired
    private IUserAccountService userAccountService;

    @Autowired
    private ISysRankService rankService;

    @Override
    public synchronized void saveShoppingCart(UserShoppingCart userShoppingCart){
        Integer sid = userShoppingCart.getSid();
        Timestamp nowDate = DateUtil.getNowDate();
        UserShoppingCart userShoppingCartDB = getUserShoppingCartBySid(sid, userShoppingCart.getShoppingType(), userShoppingCart.getUid());
        if(userShoppingCartDB == null) {
            CommoditySize commoditySize = commodityService.getCommoditySize(sid);
            CommodityColor commodityColor = commodityService.getCommodityColor(commoditySize.getCoid());
            CommodityInfo commodityInfo = commodityService.getCommodityInfo(commodityColor.getCid());
            userShoppingCart.setCoid(commodityColor.getCoid());
            userShoppingCart.setCid(commodityInfo.getCid());
            userShoppingCart.setCreateTime(nowDate);
            baseDao.save(userShoppingCart, null);
        }else{
            userShoppingCartDB.setCount(userShoppingCartDB.getCount()+userShoppingCart.getCount());
            userShoppingCartDB.setUpdateTime(nowDate);
            baseDao.save(userShoppingCartDB, userShoppingCartDB.getScid());
        }
    }

    private UserShoppingCart getUserShoppingCartBySid(int sid, String shoppingType, int uid){
        return baseDao.queryByHqlFirst("FROM UserShoppingCart WHERE sid = ?1 AND shoppingType = ?2 AND uid = ?3", sid, shoppingType, uid);
    }

    @Override
    public Map<String, Object> getUserShoppingCartList(CommodityInputView commodityInputView){
        Map<String, Object> data = new HashMap();

        PageBean pageBean = getUserShoppingCarts(commodityInputView);
        List<Map<String, Object>> list = pageBean.getList();
        double sumPrice = 0;
        for(Map<String, Object> map : list){
            map.put("resourcePath", commodityService.getFmImg(StrUtil.objToInt(map.get("cid"))));
            sumPrice = Arith.add(sumPrice, Arith.mul(StrUtil.objToDouble(map.get("price")), StrUtil.objToInt(map.get("count"))));
        }

        data.put("pageBean", pageBean);
        data.put("sumPrice", sumPrice);
        return data;
    }

    private PageBean getUserShoppingCarts(CommodityInputView commodityInputView){
        Integer uid = commodityInputView.getUid();
        String shoppingType = commodityInputView.getShoppingType();

        StringBuilder headSql = new StringBuilder("SELECT usc.scid, usc.count, ci.commName, ci.price, cc.colorName, cs.size, ci.cid");
        StringBuilder bodySql = new StringBuilder(" FROM user_shopping_cart usc, commodity_size cs, commodity_color cc, commodity_info ci");
        StringBuilder whereSql = new StringBuilder(" WHERE usc.sid = cs.sid AND cs.coid = cc.coid AND cc.cid = ci.cid");
        if(uid != null){
            whereSql.append(" AND usc.uid = :uid");
        }
        if(StrUtil.isNotBlank(shoppingType)){
            whereSql.append(" AND usc.shoppingType = :shoppingType");
        }
        return super.getPageBean(headSql, bodySql, whereSql, commodityInputView);
    }

    @Override
    public void deleteUserShoppingCart(String scids, int userId){
        String[] scidArr = scids.split(",");
        for(String scid : scidArr) {
            UserShoppingCart userShoppingCart = getUserShoppingCart(StrUtil.objToInt(scid));
            if (userId == userShoppingCart.getUid()) {
                baseDao.delete(userShoppingCart);
            }
        }
    }

    @Override
    public UserShoppingCart getUserShoppingCart(int scid){
        return baseDao.getToEvict(UserShoppingCart.class, scid);
    }

    @Override
    public Map<String, Object> settlement(String scids, int uid){
        List<Map<String, Object>> settlement = getSettlement(scids, uid);
        double sumPrice = 0;
        for(Map<String, Object> map : settlement){
            map.put("resourcePath", commodityService.getFmImg(StrUtil.objToInt(map.get("cid"))));
            sumPrice = Arith.add(sumPrice, Arith.mul(StrUtil.objToDouble(map.get("price")), StrUtil.objToInt(map.get("count"))));
        }
        UserAccount userAccount = userAccountService.getUserAccount(uid);
        Map<String, Object> data = new HashMap(4, 1);
        data.put("list", settlement);
        data.put("sumPrice", sumPrice);
        //用户优惠券列表
        data.put("coupons", userCouponService.getUserEffectiveCoupons(uid));
        //用户衣橱币
        data.put("ycoid", userAccount.getYcoid());
        //用户折扣
        data.put("discount", rankService.getRankInfoByRank(userAccount.getRank()).getRankDiscount());
        return data;
    }

    private List<Map<String, Object>> getSettlement(final String scids, final int uid) {
        StringBuilder sql = new StringBuilder("SELECT usc.scid, usc.count, ci.commName, ci.price, cc.colorName, cs.size, ci.cid");
        sql.append(" FROM user_shopping_cart usc, commodity_size cs, commodity_color cc, commodity_info ci");
        sql.append(" WHERE usc.sid = cs.sid AND cs.coid = cc.coid AND cc.cid = ci.cid AND usc.uid = :uid");
        sql.append(" AND usc.scid IN(:scids)");
        return baseDao.queryBySql(sql.toString(), new HashMap() {{
            putAll(SQLUtil.getInToSQL("scids", scids));
            put("uid", uid);
        }});
    }

    @Override
    public Map<String, Object> settlementCount(UserCouponInputView userCouponInputView, int uid) throws ParseException{
        List<Map<String, Object>> settlement = getSettlement(userCouponInputView.getScids(), uid);
        double sumPrice = 0;
        for(Map<String, Object> map : settlement){
            sumPrice = Arith.add(sumPrice, Arith.mul(StrUtil.objToDouble(map.get("price")), StrUtil.objToInt(map.get("count"))));
        }

        Map<String, Object> data = new HashMap<>(1, 1);
        //乘以折扣(四舍五入)
        data.put("sumPrice", countDiscount(sumPrice, userCouponInputView.getServiceType(), userCouponInputView.getCpid(), uid));
        return data;
    }

    @Override
    public double countDiscount(double sumPrice, String serviceType, Integer cpid, int uid) throws ParseException{
        UserAccount userAccount = userAccountService.getUserAccount(uid);
        if(IDBConstant.LOGIC_STATUS_YES.equals(serviceType)){ //使用优惠券
            UserCouponInfo userCouponInfo = userCouponService.getUserCouponInfo(cpid, uid);
            if(userCouponInfo != null) {
                sumPrice = Arith.sub(sumPrice, userCouponInfo.getCouponPrice().doubleValue());
            }
        }else if(IDBConstant.LOGIC_STATUS_NO.equals(serviceType)){ //使用衣橱币
            sumPrice = Arith.sub(sumPrice, userAccount.getYcoid());
        }
        //乘以折扣(四舍五入)
        SysRankInfo rankInfoByRank = rankService.getRankInfoByRank(userAccount.getRank());
        return StrUtil.roundKeepTwo(Arith.mul(sumPrice, rankInfoByRank.getRankDiscount().doubleValue()));
    }

}
