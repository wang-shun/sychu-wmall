package com.wardrobe.platform.service.impl;

import com.wardrobe.common.annotation.Desc;
import com.wardrobe.common.bean.PageBean;
import com.wardrobe.common.constant.IDBConstant;
import com.wardrobe.common.constant.IPlatformConstant;
import com.wardrobe.common.exception.MessageException;
import com.wardrobe.common.po.*;
import com.wardrobe.common.util.Arith;
import com.wardrobe.common.util.DateUtil;
import com.wardrobe.common.util.SQLUtil;
import com.wardrobe.common.util.StrUtil;
import com.wardrobe.common.view.OrderInputView;
import com.wardrobe.platform.service.*;
import com.wardrobe.wx.WeiXinConnector;
import com.wardrobe.wx.http.HttpConnect;
import com.wardrobe.wx.util.ConfigUtil;
import com.wardrobe.wx.util.PayCommonUtil;
import com.wardrobe.wx.util.SignUtil;
import com.wardrobe.wx.util.XMLUtil;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.*;

/**
 * Created by cxs on 2018/8/6.
 */
@Service
public class OrderServiceImpl extends BaseService implements IOrderService {

    private Logger logger = Logger.getLogger(OrderServiceImpl.class);

    @Autowired
    private IUserTransactionsService userTransactionsService;

    @Autowired
    private IUserAccountService userAccountService;

    @Autowired
    private IUserShoppingCartService userShoppingCartService;

    @Autowired
    private ICommodityService commodityService;

    @Autowired
    private ISysDeviceService sysDeviceService;

    private UserOrderInfo getUserOrderInfo(int oid){
        return baseDao.getToEvict(UserOrderInfo.class, oid);
    }

    private ReserveOrderInfo getReserveOrderInfo(int roid){
        return baseDao.getToEvict(ReserveOrderInfo.class, roid);
    }

    private UserOrderInfo getUserOrderInfoAndDetails(int oid){
        UserOrderInfo userOrderInfo = getUserOrderInfo(oid);
        userOrderInfo.setUserOrderDetails(getUserOrderDetails(oid));
        return userOrderInfo;
    }

    private List<UserOrderDetail> getUserOrderDetails(int oid){
        return baseDao.queryByHql("FROM UserOrderDetail WHERE oid = ?", oid);
    }

    @Override
    public synchronized Integer saveRechargeOrderInfo(UserOrderInfo userOrderInfo, SysDict sysDict, int uid) {
        Timestamp nowDate = DateUtil.getNowDate();
        userOrderInfo.setUid(uid);
        userOrderInfo.setCreateTime(nowDate);
        userOrderInfo.setOrderType(IDBConstant.LOGIC_STATUS_NO); //充值
        userOrderInfo.setPayStatus(IDBConstant.LOGIC_STATUS_NO);
        userOrderInfo.setStatus(IDBConstant.LOGIC_STATUS_YES);
        userOrderInfo.setOno(getOno());
        baseDao.save(userOrderInfo, null);

        int oid = userOrderInfo.getOid();

        //充值金额
        UserOrderDetail userOrderDetail = new UserOrderDetail();
        userOrderDetail.setCreateTime(nowDate);
        userOrderDetail.setItemCount(1);
        userOrderDetail.setItemName("充值金额");
        userOrderDetail.setItemPrice(userOrderInfo.getPriceSum());
        userOrderDetail.setItemPriceSum(userOrderDetail.getItemPrice());
        userOrderDetail.setOid(oid);
        baseDao.save(userOrderDetail, null);

        //赠送金额
        userOrderDetail = new UserOrderDetail();
        userOrderDetail.setItemType(IDBConstant.LOGIC_STATUS_NO); //充值赠送类型
        userOrderDetail.setCreateTime(nowDate);
        userOrderDetail.setItemCount(1);
        userOrderDetail.setItemName("赠送金额");
        userOrderDetail.setItemPrice(Arith.conversion(StrUtil.objToDouble(sysDict.getDictValue())));
        userOrderDetail.setItemPriceSum(userOrderDetail.getItemPrice());
        userOrderDetail.setOid(oid);
        baseDao.save(userOrderDetail, null);

        return oid;
    }

    private boolean existOrderByNo(String ono){
        return baseDao.queryBySqlFirst("SELECT 1 FROM user_order_info WHERE ono = ?1", ono) != null;
    }

    @Desc("生成不重复的订单编号")
    private String getOno(){
        //生成重复的订单编号
        String ono = IPlatformConstant.ONO + StrUtil.objToStr(StrUtil.initCode(6));
        while (existOrderByNo(ono)){
            ono = IPlatformConstant.ONO + StrUtil.objToStr(StrUtil.initCode(6));
        }
        return ono;
    }

    @Override
    public synchronized Integer saveOrderInfo(UserOrderInfo userOrderInfo, String scids, int uid) throws ParseException{ //购物车ids，多个逗号分隔
        Timestamp nowDate = DateUtil.getNowDate();
        userOrderInfo.setUid(uid);
        userOrderInfo.setCreateTime(nowDate);
        userOrderInfo.setOrderType(IDBConstant.LOGIC_STATUS_YES);
        userOrderInfo.setPayStatus(IDBConstant.ORDER_PAY_STATUS_NO);
        userOrderInfo.setStatus(IDBConstant.ORDER_STATUS_NORMAL);
        userOrderInfo.setOno(getOno()); //订单编号
        baseDao.save(userOrderInfo, null);

        int oid = userOrderInfo.getOid();
        String[] scidArr = scids.split(",");
        double priceSum = 0;
        for(String scid : scidArr) { //待处理积分与优惠券问题
            UserShoppingCart userShoppingCart = userShoppingCartService.getUserShoppingCart(StrUtil.objToInt(scid));
            Integer cid = userShoppingCart.getCid();
            UserOrderDetail userOrderDetail = new UserOrderDetail();
            userOrderDetail.setCreateTime(nowDate);
            userOrderDetail.setCid(cid);

            CommodityInfo commodityInfo = commodityService.getCommodityInfo(cid);
            userOrderDetail.setItemCount(userShoppingCart.getCount());
            userOrderDetail.setItemName(commodityInfo.getCommName());
            userOrderDetail.setItemPrice(commodityInfo.getPrice());

            CommodityColor commodityColor = commodityService.getCommodityColorByCid(cid);
            userOrderDetail.setItemColor(commodityColor.getColorName());

            CommoditySize commoditySize = commodityService.getCommoditySize(userShoppingCart.getSid());
            userOrderDetail.setItemSize(commoditySize.getSize());

            userOrderDetail.setItemPriceSum(Arith.conversion(Arith.mul(commodityInfo.getPrice().doubleValue(), userShoppingCart.getCount())));
            userOrderDetail.setOid(oid);
            userOrderDetail.setItemImg(commodityService.getFmImg(cid));
            baseDao.save(userOrderDetail, null);

            priceSum = Arith.add(priceSum, userOrderDetail.getItemPriceSum().doubleValue());

            //删除购物车
            baseDao.delete(userShoppingCart);
        }

        userOrderInfo.setPriceSum(Arith.conversion(priceSum));  //商品原总价
        //支付价格：之后减去优惠部分
        userOrderInfo.setPayPrice(Arith.conversion(userShoppingCartService.countDiscount(userOrderInfo.getPriceSum().doubleValue(), userOrderInfo.getServiceType(), userOrderInfo.getCpid(), uid)));
        baseDao.save(userOrderInfo, oid);
        return oid;
    }

    private boolean existReserveOrderByNo(String rno){
        return baseDao.queryBySqlFirst("SELECT 1 FROM reserve_order_info WHERE rno = ?1", rno) != null;
    }

    @Desc("生成不重复的预约单编号")
    private String getRno(){
        //生成重复的订单编号
        String rno = IPlatformConstant.RNO + StrUtil.objToStr(StrUtil.initCode(6));
        while (existReserveOrderByNo(rno)){
            rno = IPlatformConstant.RNO + StrUtil.objToStr(StrUtil.initCode(6));
        }
        return rno;
    }

    @Override
    public synchronized Integer saveReserveOrderInfo(ReserveOrderInfo orderInfo, String scids, int uid) throws ParseException{ //购物车ids，多个逗号分隔
        if(StrUtil.isBlank(scids)) throw new MessageException("操作错误!");
        Integer did = orderInfo.getDid();

        SysDeviceInfo sysDeviceInfo = sysDeviceService.getDeviceInfo(did);
        if(sysDeviceInfo == null) throw new MessageException("当前试衣间不存在，请重新选择！");

        ReserveOrderInfo existNowReserve = isExistNowReserve(uid);
        if(existNowReserve != null) throw new MessageException("您已经预约了" + DateUtil.dateToString(new Date(existNowReserve.getReserveStartTime().getTime()), DateUtil.YYYYMMDDHHMMSS) + "到" +  DateUtil.dateToString(new Date(existNowReserve.getReserveEndTime().getTime()), DateUtil.YYYYMMDDHHMMSS) + "，不能重复预约");

        if(!IDBConstant.LOGIC_STATUS_YES.equals(sysDeviceInfo.getStatus())) throw new MessageException("当前试衣间未开启，请稍候再试！");

        Timestamp reserveStartTime = orderInfo.getReserveStartTime();
        Timestamp reserveEndTime = orderInfo.getReserveEndTime();

        String startTime = sysDeviceInfo.getStartTime();
        String endTime = sysDeviceInfo.getEndTime();
        Date startDTime = DateUtil.getHHMM(startTime);
        Date endDTime = DateUtil.getHHMM(endTime);
        if(DateUtil.getHHMMSS(reserveStartTime).getTime() < startDTime.getTime() || DateUtil.getHHMMSS(reserveEndTime).getTime() > endDTime.getTime()) throw new MessageException("试衣间开启时间为：" + startTime + "到" + endTime);

        if(isReserveByDate(reserveStartTime, reserveEndTime)) throw new MessageException("当前时间有冲突，请重新选择！");

        SysDeviceControl sysDeviceControl = sysDeviceService.getDistributionDeviceControl(did, reserveStartTime, reserveEndTime);
        if(sysDeviceControl == null) throw new MessageException("当前预约已满，请稍候再试！");

        Timestamp nowDate = DateUtil.getNowDate();
        orderInfo.setDcid(sysDeviceControl.getDcid());
        orderInfo.setUid(uid);
        orderInfo.setCreateTime(nowDate);
        orderInfo.setPayStatus(IDBConstant.ORDER_PAY_STATUS_NO);
        orderInfo.setStatus(IDBConstant.ORDER_STATUS_NORMAL);
        orderInfo.setRno(getRno()); //预约单编号
        baseDao.save(orderInfo, null);

        int oid = orderInfo.getRoid();
        String[] scidArr = scids.split(",");
        double priceSum = 0;
        for(String scid : scidArr) { //待处理积分与优惠券问题
            UserShoppingCart userShoppingCart = userShoppingCartService.getUserShoppingCart(StrUtil.objToInt(scid));
            Integer cid = userShoppingCart.getCid();
            ReserveOrderDetail orderDetail = new ReserveOrderDetail();
            orderDetail.setCreateTime(nowDate);
            orderDetail.setCid(cid);

            CommodityInfo commodityInfo = commodityService.getCommodityInfo(cid);
            orderDetail.setResItemCount(userShoppingCart.getCount());
            orderDetail.setResItemName(commodityInfo.getCommName());
            orderDetail.setResItemPrice(commodityInfo.getPrice());

            CommodityColor commodityColor = commodityService.getCommodityColorByCid(cid);
            orderDetail.setResItemColor(commodityColor.getColorName());

            CommoditySize commoditySize = commodityService.getCommoditySize(userShoppingCart.getSid());
            orderDetail.setResItemSize(commoditySize.getSize());

            orderDetail.setResItemPriceSum(Arith.conversion(Arith.mul(commodityInfo.getPrice().doubleValue(), userShoppingCart.getCount())));
            orderDetail.setRoid(oid);
            orderDetail.setResItemImg(commodityService.getFmImg(cid));
            baseDao.save(orderDetail, null);

            priceSum = Arith.add(priceSum, orderDetail.getResItemPriceSum().doubleValue());

            //预约购物车商品变为普通购物车商品
            userShoppingCart.setShoppingType(IDBConstant.LOGIC_STATUS_YES);
            baseDao.save(userShoppingCart, userShoppingCart.getScid());
        }

        orderInfo.setPriceSum(Arith.conversion(priceSum));  //商品原总价
        orderInfo.setPayPrice(orderInfo.getPriceSum()); //支付价格：之后减去优惠部分
        baseDao.save(orderInfo, oid);
        return oid;
    }

    @Desc("预约时间是否有交集")
    private boolean isReserveByDate(Timestamp startDate, Timestamp endDate){
        Object obj = baseDao.getUniqueObjectResult("SELECT 1 FROM reserve_order_info r WHERE NOT (r.reserveEndTime <= ?1 OR r.reserveStartTime >= ?2) AND r.status = ?3", startDate, endDate, IDBConstant.LOGIC_STATUS_YES);
        return obj != null;
    }

    @Desc("存在当前时间之前的预约，则不能进行预约")
    private ReserveOrderInfo isExistNowReserve(int uid){
        return baseDao.queryByHqlFirst("SELECT r FROM ReserveOrderInfo r WHERE r.reserveEndTime >= NOW() AND r.uid = ?1 AND r.status = ?2 ORDER BY r.roid DESC", uid, IDBConstant.LOGIC_STATUS_YES);
    }

    @Desc("获得未支付并在当前时间之前的最后一个未支付的订单（一个时间段只能预约一次）")
    @Override
    public Map<String, Object> getNowReserveOrderInfo(int uid){
        StringBuilder sql = new StringBuilder("SELECT r.roid, DATE(r.reserveStartTime) resDate, TIME(r.reserveStartTime) resStartTime, TIME(r.reserveEndTime) resEndTime, sd.name sdName, sd.address, a.areaNameFull, sdc.name sdcName FROM reserve_order_info r, sys_device_control sdc, sys_device_info sd, sys_area a");
        sql.append(" WHERE r.dcid = sdc.dcid AND sdc.did = sd.did AND sd.areaId = a.areaId AND r.reserveEndTime >= NOW() AND r.uid = ?1 AND r.status = ?2 AND r.payStatus = ?3 ORDER BY r.roid DESC");
        List<Map<String, Object>> list = baseDao.queryBySql(sql.toString(), uid, IDBConstant.LOGIC_STATUS_YES, IDBConstant.LOGIC_STATUS_NO);
        list.parallelStream().forEach(map -> {
            String startTime = StrUtil.objToStr(map.get("resStartTime"));
            String endTime = StrUtil.objToStr(map.get("resEndTime"));
            map.put("resStartTime", startTime.substring(0, startTime.lastIndexOf(":")));
            map.put("resEndTime", endTime.substring(0, endTime.lastIndexOf(":")));
            map.put("areaNameFull", StrUtil.objToStr(map.get("areaNameFull")).replace("->", " "));
        });
        Map<String, Object> data = new HashMap();
        data.put("list", list);
        return data;
    }

    @Override
    public Map<String, Object> getNowReserveOrderDetail(int uid, int roid){
        Map<String, Object> data = new HashMap();
        List<Map<String, Object>> list = baseDao.queryBySql("SELECT rod.* FROM reserve_order_detail rod, reserve_order_info r  WHERE rod.roid = r.roid AND r.uid = ?1 AND rod.roid = ?2", new Integer[]{uid, roid});
        double sumPrice = 0;
        for(Map<String, Object> map : list){
            sumPrice = Arith.add(sumPrice, StrUtil.objToDouble(map.get("resItemPriceSum")));
        }
        data.put("sumPrice", sumPrice);
        data.put("list", list);
        return data;
    }

    @Override
    public ReserveOrderInfo getLastReserveOrderInfo(int uid){
        return baseDao.queryByHqlFirst("FROM ReserveOrderInfo r WHERE r.uid = ?1 ORDER BY r.roid DESC LIMIT 1", uid);
    }

    @Override
    public void saveCancelReserveOrder(int uid, int roid){
        ReserveOrderInfo reserveOrderInfo = getReserveOrderInfo(roid);
        if(reserveOrderInfo.getUid() != uid) throw new MessageException("错误");
        reserveOrderInfo.setStatus(IDBConstant.LOGIC_STATUS_NO); //取消预约
        reserveOrderInfo.setUpdateTime(DateUtil.getNowDate());
        baseDao.save(reserveOrderInfo, roid);
    }

    @Override
    public Map<Object, Object> wxPayPackage(OrderInputView orderInputView, String openId) throws Exception {
        Integer oId = orderInputView.getOrderId();

        UserOrderInfo orderInfo = getUserOrderInfo(oId); //openId = "oTK5utzXi_A2q8aus80Y60__LzY0";
        // 1 参数
        // 订单号
        String orderId = orderInfo.getOid() + "_" + System.currentTimeMillis();
        // 附加数据 原样返回
        String attach = "cxs";
        // 总金额以分为单位，不带小数点
        if(orderInfo.getPayPrice().doubleValue() <= 0) { //无需支付
            orderInfo.setPayStatus(IDBConstant.LOGIC_STATUS_YES);
            orderInfo.setPayTime(DateUtil.getNowDate());
            baseDao.save(orderInfo, oId);
            return new HashMap(){{put("ok", IDBConstant.LOGIC_STATUS_YES);}};
        }
        String totalFee = StrUtil.getMoney(StrUtil.objToStr(orderInfo.getPayPrice().doubleValue())); //这里是支付金额

        // 订单生成的机器 IP
        String spbill_create_ip = "127.0.0.1";
        // 这里notify_url是 支付完成后微信发给该链接信息，可以判断会员是否支付成功，改变订单状态等。
        String notify_url = ConfigUtil.NOTIFY_URL;
        String trade_type = "JSAPI";

        // ---必须参数
        // 随机字符串
        String nonce_str = StrUtil.getNonceStr();

        // 商品描述根据情况修改
        String body = IDBConstant.TRANSACTIONS_TYPE_ZF.equals(orderInfo.getOrderType()) ? "商品购买" : "充值";

        // 商户订单号
        String out_trade_no = orderId;

        SortedMap<Object, Object> packageParams = new TreeMap<Object, Object>();
        packageParams.put("appid", ConfigUtil.APPID);
        packageParams.put("mch_id", ConfigUtil.MCH_ID);  //商户号
        packageParams.put("nonce_str", nonce_str);
        packageParams.put("body", body);
        packageParams.put("attach", attach);
        packageParams.put("out_trade_no", out_trade_no);

        // 这里写的金额为1 分到时修改
        packageParams.put("total_fee", totalFee);
        packageParams.put("spbill_create_ip", spbill_create_ip);
        packageParams.put("notify_url", notify_url);

        packageParams.put("trade_type", trade_type);
        packageParams.put("openid", openId);
        String sign = PayCommonUtil.createSign("UTF-8", packageParams);
        packageParams.put("sign", sign);

        String requestXML = PayCommonUtil.getRequestXml(packageParams);
        logger.info("requestXML：" + requestXML);
        Map map = XMLUtil.doXMLParse(HttpConnect.httpsRequestStr(ConfigUtil.UNIFIED_ORDER_URL, "POST", requestXML));

        String prepay_id = map.get("prepay_id").toString();
        logger.info("获取到的预支付ID：" + prepay_id);

        //获取prepay_id后，拼接最后请求支付所需要的package

        SortedMap<Object, Object> finalpackage = new TreeMap<Object, Object>();
        String timestamp = SignUtil.getTimeStamp();
        String packages = "prepay_id="+prepay_id;
        finalpackage.put("appId", ConfigUtil.APPID);
        finalpackage.put("timeStamp", timestamp);
        finalpackage.put("nonceStr", nonce_str);
        finalpackage.put("package", packages);
        finalpackage.put("signType", ConfigUtil.SIGN_TYPE);
        //要签名
        String finalsign = PayCommonUtil.createSign("UTF-8", finalpackage);

        String finaPackage = "\"appId\":\"" + ConfigUtil.APPID + "\",\"timeStamp\":\"" + timestamp
                + "\",\"nonceStr\":\"" + nonce_str + "\",\"package\":\""
                + packages + "\",\"signType\" : \"MD5" + "\",\"paySign\":\""
                + finalsign + "\"";
       /* timeStamp: param.data.timeStamp,//记住，这边的timeStamp一定要是字符串类型的，不然会报错，我这边在java后端包装成了字符串类型了
                nonceStr: param.data.nonceStr,
        package: param.data.package,
        signType: 'MD5',
                paySign: param.data.paySign,*/
        logger.info("V3 jsApi package:" + finaPackage);
        finalpackage.put("finaPackage", finaPackage);
        return finalpackage;
        //logger.info("V3 jsApi package:"+finaPackage);
        //return finaPackage;
    }

    @Override
    public synchronized void saveAsynNotify(String msgxml, HttpServletResponse response) throws Exception{
        Map map = XMLUtil.doXMLParse(msgxml);

        String result_code=(String) map.get("result_code");
        String out_trade_no  = (String) map.get("out_trade_no");
        String total_fee  = (String) map.get("total_fee");
        String sign  = (String) map.get("sign");
        Double amount = new Double(total_fee) / 100;//获取订单金额
        System.out.println("saveAsynNotify===>" + result_code);
        if(result_code.equals("SUCCESS")){
            System.out.println("qianming_success");
            //验证签名
            String fee_type  = (String) map.get("fee_type");
            String bank_type  = (String) map.get("bank_type");
            String cash_fee  = (String) map.get("cash_fee");
            String is_subscribe  = (String) map.get("is_subscribe");
            String nonce_str  = (String) map.get("nonce_str");
            String openid  = (String) map.get("openid");
            String return_code  = (String) map.get("return_code");
            String sub_mch_id  = (String) map.get("sub_mch_id");
            String time_end  = (String) map.get("time_end");
            String trade_type  = (String) map.get("trade_type");
            String transaction_id  = (String) map.get("transaction_id");
            String attach  = (String) map.get("attach");

            //需要对以下字段进行签名
            SortedMap<Object, Object> packageParams = new TreeMap<Object, Object>();
            packageParams.put("appid", ConfigUtil.APPID);
            packageParams.put("bank_type", bank_type);
            packageParams.put("cash_fee", cash_fee);
            packageParams.put("fee_type", fee_type);
            packageParams.put("is_subscribe", is_subscribe);
            packageParams.put("mch_id", ConfigUtil.MCH_ID);
            packageParams.put("nonce_str", nonce_str);
            packageParams.put("openid", openid);
            packageParams.put("out_trade_no", out_trade_no);
            packageParams.put("result_code", result_code);
            packageParams.put("return_code", return_code);
            packageParams.put("sub_mch_id", sub_mch_id);
            packageParams.put("time_end", time_end);
            packageParams.put("total_fee", total_fee); //用自己系统的数据：订单金额
            packageParams.put("trade_type", trade_type);
            packageParams.put("transaction_id", transaction_id);
            packageParams.put("attach", attach);
            System.out.println("packageParams===>" + packageParams);
            String endsign = PayCommonUtil.createSign("UTF-8", packageParams);
            System.out.println("qianming_1");
            if(sign.equals(endsign)){//验证签名是否正确  官方签名工具地址：https://pay.weixin.qq.com/wiki/tools/signverify/
                System.out.println("qianming_1_success");
                //处理自己的业务逻辑
                logger.info("支付成功!");
                Integer oId = StrUtil.objToInt(out_trade_no.substring(0, out_trade_no.indexOf("_")));
                UserOrderInfo userOrderInfo = getUserOrderInfoAndDetails(oId);
                if(!IDBConstant.LOGIC_STATUS_YES.equals(userOrderInfo.getPayStatus())){ //未支付状态
                    userOrderInfo.setPayStatus(IDBConstant.LOGIC_STATUS_YES);
                    baseDao.save(userOrderInfo, userOrderInfo.getOid());
                    //userOrderInfo.setPayPrice(userOrderInfo.getPriceSum());
                    //充值类型需要处理用户账户金额
                    /*synchronized (OrderServiceImpl.class) {
                        if (IDBConstant.TRANSACTIONS_TYPE_ZF.equals(userOrderInfo.getOrderType())) {
                            //userAccountService.addRechargePrice(userOrderInfo.getUid(), userOrderInfo.getPayPrice());
                        }
                        //交易流水
                        userTransactionsService.addUserTransactions(userOrderInfo.getUid(), oId, userOrderInfo.getOrderType(), userOrderInfo.getPayPrice());
                    }*/
                }
            }else{
                System.out.println("qianming_1_fail");
                logger.info("sign===>" + sign);
                logger.info("endsign===>" + endsign);
                logger.info("验证签名不正确");
            }
        }else{
            System.out.println("qianming_1_null");
            response.getWriter().write(setXML("FAIL", "报文为空"));
        }
    }

    private String setXML(String return_code, String return_msg) {
        return "<xml><return_code><![CDATA[" + return_code + "]]></return_code><return_msg><![CDATA[" + return_msg + "]]></return_msg></xml>";
    }

    @Override
    public PageBean getUserOrderList(OrderInputView orderInputView){
        PageBean pageBean = getUserOrders(orderInputView);
        List<Map<String, Object>> list = pageBean.getList();
        for(Map<String, Object> map : list){
            map.put("orderDetails", getUserOrderDetail(StrUtil.objToInt(map.get("oid"))));
        }
        return pageBean;
    }

    @Override
    public Map<String, Object> getUserOrderDetail(int oid, int uid){
        UserOrderInfo userOrderInfo = getUserOrderInfo(oid);
        if(userOrderInfo.getUid() != uid) throw new MessageException("操作错误!!!");
        List<Map<String, Object>> userOrderDetail = getUserOrderDetail(oid);

        Map<String, Object> data = new HashMap<>(2, 1);
        data.put("order", userOrderInfo);
        data.put("orderDetails", userOrderDetail);
        return data;
    }

    private PageBean getUserOrders(OrderInputView orderInputView){
        Integer uid = orderInputView.getUid();
        StringBuilder headSql = new StringBuilder("SELECT *");
        StringBuilder bodySql = new StringBuilder(" FROM user_order_info uoi");
        StringBuilder whereSql = new StringBuilder(" WHERE 1=1");
        if(uid != null){
            whereSql.append(" AND uoi.uid = :uid");
        }
        whereSql.append(" ORDER BY uoi.createTime DESC");
        return super.getPageBean(headSql, bodySql, whereSql, orderInputView);
    }

    private List<Map<String, Object>> getUserOrderDetail(int oid){
        return baseDao.queryBySql("SELECT * FROM user_order_detail WHERE oid = ?1", oid);
    }

    @Override
    public PageBean getUserOrderListIn(OrderInputView orderInputView){
        PageBean pageBean = getUserOrdersIn(orderInputView);
        List<Map<String, Object>> list = pageBean.getList();
        list.parallelStream().forEach(map -> {
            map.put("payStatusName", dictService.getDictValue(IDBConstant.ORDER_PAY_STATUS, StrUtil.objToStr(map.get("payStatus"))));
            map.put("statusName", dictService.getDictValue(IDBConstant.ORDER_STATUS, StrUtil.objToStr(map.get("status"))));
        });
        return pageBean;
    }

    private PageBean getUserOrdersIn(OrderInputView orderInputView){
        String status = orderInputView.getStatus();
        String ono = orderInputView.getOno();
        StringBuilder headSql = new StringBuilder("SELECT uoi.*, ui.nickname");
        StringBuilder bodySql = new StringBuilder(" FROM user_order_info uoi, user_info ui");
        StringBuilder whereSql = new StringBuilder(" WHERE uoi.uid = ui.uid");
        Map inMap = new HashMap<>();
        if(StrUtil.isNotBlank(status)){
            whereSql.append(" AND uoi.status IN(:statusArr)");
            inMap.putAll(SQLUtil.getInToSQL("statusArr", status));
        }
        if(StrUtil.isNotBlank(ono)){
            whereSql.append(" AND uoi.ono = :ono");
        }
        whereSql.append(" ORDER BY uoi.createTime DESC");
        return super.getPageBean(headSql, bodySql, whereSql, orderInputView, inMap);
    }

    @Override
    public PageBean getReserveOrderInfoList(OrderInputView orderInputView){
        PageBean pageBean = getReserveOrderInfos(orderInputView);
        return pageBean;
    }

    private PageBean getReserveOrderInfos(OrderInputView orderInputView){
        String ono = orderInputView.getOno();
        StringBuilder headSql = new StringBuilder("SELECT roi.*, ui.nickname, sdc.name lockName, sdi.name deviceName");
        StringBuilder bodySql = new StringBuilder(" FROM reserve_order_info roi, user_info ui, sys_device_control sdc, sys_device_info sdi");
        StringBuilder whereSql = new StringBuilder(" WHERE roi.uid = ui.uid AND roi.dcid = sdc.dcid AND sdc.did = sdi.did");

        if(StrUtil.isNotBlank(ono)){
            whereSql.append(" AND roi.rno = :ono");
        }

        whereSql.append(" ORDER BY roi.createTime DESC");
        return super.getPageBean(headSql, bodySql, whereSql, orderInputView);

    }

}
