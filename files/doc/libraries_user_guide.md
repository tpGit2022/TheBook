[TOC]


ui-selector

用于实现不同 Android 系统长按复制 Dialog 的同一显示。

```
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTvTest = (TextView) findViewById(R.id.tv_test);
        //mTvTest.setTextIsSelectable(true);

        mSelectableTextHelper = new SelectableTextHelper.Builder(mTvTest)
            .setSelectedColor(getResources().getColor(R.color.selected_blue))
            .setCursorHandleSizeInDp(20)
            .setCursorHandleColor(getResources().getColor(R.color.cursor_handle_color))
            .build();

        mSelectableTextHelper.setSelectListener(new OnSelectListener() {
            @Override
            public void onTextSelected(CharSequence content) {

            }
        });
        initView();
    }
```