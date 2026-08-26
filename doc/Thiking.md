
# 改数据库还是改代码？

每当插入一条新纪录时，stat_month 应该跟随 daily 的变化而变化，这个是由数据库来联动还是由代码来控制?
Android 的 Room 数据库框架支不支持一次执行多条语句，支不支持事务处理，支不支持存储过程?

# 网格

折线图本身是为了看整体的发展趋势，没必要显示干扰视线的网格线。相反点击出现的 GuideLine 显示(x,y) 就很有必要了

# 数据存储问题

Android 数据的外部存储最好按照 Google 官方提供的例子来，`Environment.getExternalStorageDirectory()` 这一行代码一直被提示 `Deprecated` 
正确的使用方式是通过区分文件类型，借助 MediaStore 和 SAF(Storage Access Framework) 两者来完成。
